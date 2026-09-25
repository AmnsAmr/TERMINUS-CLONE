# Terminus — Consolidated Codebase Audit & Refactoring Handoff

## Purpose

This document consolidates the read-only audits performed by the coding agent across the Terminus Android/Kotlin codebase.

The next agent should use this as the working handoff:
- preserve the confirmed findings;
- distinguish confirmed defects from context-dependent risks and findings explicitly ruled out;
- make changes incrementally;
- compile and test after each batch;
- do not perform a broad rewrite merely to address every theoretical concern.

No source files were modified during the audits.

---

# 1. Audit scope and methodology

The agent reported auditing:
- all 87 Kotlin source files under `app/src/main/java`;
- all 47 Kotlin files under `ui/` in a dedicated UI pass;
- the manifest and Gradle configuration;
- repository, DAO, database, provider, playback, synchronization, navigation, Compose UI, and component call paths.

The audit was read-only and used full-source inspection rather than only grep/pattern matching.

For non-obvious findings, the agent empirically verified behavior where possible:
- compiled Kotlin snippets using the project's Kotlin 2.0.21 compiler;
- inspected bytecode for `TweenSpec`;
- traced data/query/call chains;
- checked actual callers to distinguish reachable bugs from merely theoretical ones.

A particularly important audit principle was to record things that looked dangerous but were actually safe, so they are not unnecessarily "fixed."

The codebase has:
- **zero `!!` operators** in app source;
- **no unchecked casts** in app source.

Therefore, the crash surface is concentrated in:
- unguarded API/IO calls;
- Compose key invariants;
- coroutine/lifecycle behavior;
- malformed/restored navigation arguments;
- resource/data consistency issues.

---

# 2. Executive summary

The codebase is not fundamentally broken. Most basic nullability and chart safety checks are already defensive.

The important problems cluster into a few architectural areas:

1. **Synchronous/blocking reads inside hot networking/playback paths**
2. **Library synchronization that is not serialized/atomic**
3. **A small number of real hard crashes**
4. **Coroutine lifecycle and cancellation mistakes**
5. **Playback/statistics data correctness issues**
6. **Database scalability and indexing**
7. **Production hardening/security**
8. **Compose animation and rendering performance**

The highest-priority confirmed problems are:

- duplicate `LazyColumn` key on Stats → SONG;
- unhandled library sync exceptions from Home/Manage Sources;
- `PlaybackController` connection failure/reconnection handling;
- `runBlocking` on every HTTP request;
- `runBlocking` in the ExoPlayer data-source resolver;
- unsafely concurrent `syncLibrary()`;
- play-count double-recording;
- unbounded SQL `IN (:ids)` for liked songs;
- playlist import without a transaction;
- destructive Room migrations.

---

# 3. Performance / "why the app feels heavy"

## 3.1 `runBlocking` on every HTTP request — highest-priority performance issue

### Location

`di/NetworkModule.kt:41`

The OkHttp interceptor calls approximately:

```kotlin
runBlocking {
    prefsRepo.preferences.first()
}
```

to obtain server URL/credentials.

### Why this is expensive

This is a disk-backed DataStore read executed on an OkHttp worker thread for **every HTTP request**.

This includes requests that can happen frequently, such as Coil artwork loads.

The audit identified several consequences:
- worker-thread blocking;
- repeated DataStore reads;
- serialization around DataStore's single-file lock;
- unnecessary latency and thread pressure.

The audit identified this as the **single largest source of latency/thread pressure**.

### Recommended fix

Replace the per-request DataStore read with a cached configuration mechanism, for example:
- a `StateFlow` holding the current server configuration;
- or a carefully managed `@Volatile`/atomic cached credentials/configuration field.

The interceptor should consume already-resolved configuration rather than synchronously reading DataStore.

Do not simply move the same `runBlocking` somewhere else.

---

# 4. `runBlocking` inside the ExoPlayer data-source resolver

## Location

`playback/MusicService.kt:93`

The resolver is synchronous and currently does approximately:

```kotlin
runBlocking {
    musicRepository.getSongUri(songId)
}
```

The repository path performs:
- a Room `getById`;
- another preferences read.

### Problem

The ExoPlayer resolver is a synchronous loading path. Blocking it with `runBlocking` introduces:
- thread blocking;
- unnecessary database/preferences latency;
- potential deadlock risk depending on dispatcher/thread interactions.

### Recommended fix

Design the resolver around already-cached song/server configuration, or resolve the playback URI before the synchronous resolver is invoked.

The key requirement is:

> The synchronous resolver must not perform a blocking coroutine chain involving Room + DataStore.

This is a separate issue from the HTTP interceptor and should be addressed deliberately.

---

# 5. Library sync architecture

## 5.1 Full library sync on cold start, with duplicate scheduling

### Location

`TerminusApplication.kt:37-50`

`onCreate` enqueues:
- a 12-hour periodic worker;
- and a one-shot worker using `ExistingWorkPolicy.REPLACE`.

Each sync can perform:
- a full MediaStore query;
- a Navidrome `search3` request with `songCount = 10000`;
- loading the entire songs table into memory;
- diffing;
- upserting;
- deleting.

Relevant code paths:
- `NavidromeProvider.kt:34-40`
- `MusicRepository.kt:76-99`

### Problem

A cold start can trigger a full library synchronization unnecessarily, and the one-shot/periodic scheduling can create overlapping synchronization concerns.

### Recommended fix

Remove the cold-start `REPLACE` one-shot if it is not actually required.

Keep synchronization on explicit refresh / controlled WorkManager scheduling.

More importantly, make `syncLibrary()` itself safe against concurrent invocation.

---

# 6. `syncLibrary()` is not serialized or atomic

## Location

`MusicRepository.kt:47-99`

Current conceptual flow:

1. scan providers;
2. read all existing songs;
3. compute additions/updates/deletes;
4. upsert;
5. delete.

There is:
- no Mutex/single-flight guard;
- no transaction covering the DB mutation sequence.

### Callers

The audit identified multiple callers:
- Home refresh;
- Manage Sources;
- WorkManager;
- import completion.

### Concrete race

Possible sequence:

```text
Sync A reads DB
Sync B inserts new songs
Sync A computes deletes from stale snapshot
Sync A deletes songs that B just inserted
```

### Another issue

The upsert happens before delete.

If the process dies between these operations, the database can remain partially updated/stale.

### Recommended fix

Use a serialized sync mechanism, e.g.:

```kotlin
private val syncMutex = Mutex()

suspend fun syncLibrary() = syncMutex.withLock {
    ...
}
```

Then make the database mutation phase atomic with a Room transaction.

Important: the entire provider network scan does not necessarily need to happen inside a Room transaction. Prefer:
1. scan providers outside the transaction;
2. acquire synchronization;
3. perform the diff and DB mutation atomically.

Also ensure one failed/partial provider scan cannot cause deletion of another provider's data.

---

# 7. Provider identity collision

## Location

`SongEntity.kt`

`remoteId` is currently the sole primary key.

The audit noted that local MediaStore IDs and Navidrome IDs are not namespaced.

If the same string value occurs in two providers, one provider's row can overwrite the other with `REPLACE` semantics.

### Recommended fix

Use provider-aware identity, for example:

```text
(providerId, remoteId)
```

or another namespaced identity strategy.

Do this carefully because changing primary-key structure requires a real Room migration.

---

# 8. Hard crash: duplicate LazyColumn key in Stats SONG tab

## Location

`ui/screens/stats/StatsScreen.kt:227`

Current pattern:

```kotlin
items(state.topCategoryItems, key = { it.label }) {
    ...
}
```

For the SONG category, `label` is the song title.

### Why it crashes

The data chain is:

```text
MusicRepository
  -> SongEntity uses remoteId as identity
  -> PlayEventDao groups by songId
  -> query projects song title
  -> StatsRepository creates TopCategoryItem(label = title)
  -> StatsScreen uses title as Compose key
```

Two distinct songs can have the same title.

Example:

```text
Song A: Intro
Song B: Intro
```

The database correctly treats them as different songs, but Compose sees:

```text
key = "Intro"
key = "Intro"
```

and can throw:

```text
IllegalArgumentException: Key "Intro" was already used
```

The default Stats tab is ARTIST, so this is exposed when the user taps SONG.

### Recommended fix

Carry identity through the model:

```kotlin
data class TopCategoryItem(
    val label: String,
    val playCount: Int,
    val id: String
)
```

Then:

```kotlin
items(
    state.topCategoryItems,
    key = { it.id }
)
```

This is the cleanest fix.

Do not use display text as identity.

---

# 9. Hard crash: unhandled sync exceptions from ViewModels

## Home

### Location

`HomeViewModel.kt:50-56`

The Home screen starts the app's library sync through a bare coroutine launch.

If `syncLibrary()` throws, the exception can reach the default uncaught exception handler.

`NavidromeProvider.kt:42-44` explicitly throws when the Subsonic response is not OK.

### User-visible consequence

A configured but unreachable Navidrome server can potentially cause the app to crash during Home startup.

Also, `isSyncing` is not guaranteed to reset on failure.

### Recommended fix

Protect the operation and reset state in `finally`.

Conceptually:

```kotlin
viewModelScope.launch {
    isSyncing = true
    try {
        repository.syncLibrary()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // update UI error state
    } finally {
        isSyncing = false
    }
}
```

Do not blindly use `runCatching` around suspending operations unless cancellation is explicitly preserved.

---

# 10. Manage Sources uses `GlobalScope`

## Location

`ManageSourcesViewModel.kt:71,84-87`

Current behavior is approximately:

```kotlin
syncJob = GlobalScope.launch(Dispatchers.IO) {
    delay(1000)
    musicRepository.syncLibrary()
}
```

### Problems

1. It is not tied to the ViewModel lifecycle.
2. It can continue after the screen is destroyed.
3. A sync exception can become an uncaught process-level exception.
4. The debounce job handling is not lifecycle-safe.

### Recommended fix

Use:

```kotlin
viewModelScope.launch(Dispatchers.IO) { ... }
```

and cancel/replace the previous job.

Also cancel explicitly in `onCleared()` if the stored job requires it, although `viewModelScope` itself is lifecycle-bound.

---

# 11. MediaStore `DATA` column risk

## Locations

- `ManageSourcesViewModel.kt:57`
- `LocalMediaProvider.kt:67`

The code uses:

```kotlin
cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
```

`DATA` is deprecated and its availability is not guaranteed in the target environment.

The app targets SDK 34 and has min SDK 29.

The permission gate reduces the SecurityException risk, but it does not guarantee that the cursor contains the requested column.

### Recommended fix

Prefer:

```kotlin
val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
if (dataCol < 0) {
    // handle unsupported/missing column
}
```

and provide an explicit fallback/error state.

Do this in both relevant paths.

---

# 12. Unbounded SQL `IN (:ids)` query

## Locations

- `MusicRepository.kt:226-230`
- `SongDao.kt:50-51`

Current query:

```kotlin
@Query("SELECT * FROM songs WHERE remoteId IN (:ids)")
suspend fun getByIds(ids: List<String>): List<SongEntity>
```

Unlike other paths in `MusicRepository`, this is not chunked.

Large liked-song libraries can hit SQLite's bound-variable limit and produce:

```text
SQLiteException: too many SQL variables
```

### Recommended fix

Chunk IDs into safe batches, for example roughly 500–900 per query.

Longer term, consider a paginated repository API rather than loading the entire liked library at once.

---

# 13. Playlist import is not transactional

## Locations

`MusicRepository.kt:334-340`

Current sequence:

1. insert playlist row;
2. insert playlist-song rows.

If step 2 fails, the playlist row remains.

### Failure modes

- duplicate M3U entries can violate composite primary key:
  `(playlistId, songId)`;
- large playlists can hit SQL bind-variable limits;
- insertion failure can leave an empty or partially populated playlist.

### Recommended fix

Create a DAO-level `@Transaction` operation that:
1. inserts playlist;
2. inserts all playlist-song rows;
3. rolls back everything if any step fails.

Also decide whether duplicate M3U entries are intended:
- if not, deduplicate;
- if yes, the schema needs an ordinal/position identity instead of `(playlistId, songId)` alone.

Chunk large inserts.

---

# 14. PlaybackController connection failure

## Location

`playback/PlaybackController.kt:100-104`

The code uses a future and calls `future.get()` from a listener running on `MoreExecutors.directExecutor()`.

### Problems

- `ExecutionException` / cancellation can escape;
- `onReady()` does not execute on failure;
- no explicit connection-error state is exposed.

### More serious lifecycle problem

There is no `onDisconnected` handling.

After:

```text
MusicService.stopSelf()
MusicService.onDestroy()
```

the retained controller can remain in a state where `connect()` short-circuits because the controller field is non-null, even though the underlying service is gone.

Result:

> playback can remain dead until process death.

### Recommended fix

- handle future failure explicitly;
- clear/reset the controller when disconnected;
- provide reconnection logic;
- restore the interrupt flag if appropriate for `InterruptedException`;
- expose a recoverable connection state.

---

# 15. CancellationException is being swallowed

Broad patterns such as:

```kotlin
catch (e: Exception) { ... }
```

and:

```kotlin
runCatching { ... }
```

are problematic around suspending operations.

`CancellationException` is an `Exception`, and `runCatching` catches `Throwable`.

### Confirmed affected paths

- `LibrarySyncWorker.kt`
- `GapFinderViewModel.kt`
- `UploadViewModel.kt`
- `SettingsViewModel.kt`
- several `MusicRepository.kt` paths
- `NavidromeProvider.kt`
- Manage Sources-related operations

### Consequence

Cancelled work can be converted into:
- failure UI;
- retry results;
- continued work after the caller is gone.

For WorkManager specifically, cancellation can incorrectly become `Result.retry()`.

### Recommended pattern

```kotlin
try {
    ...
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

Do not swallow coroutine cancellation.

---

# 16. Manage Sources lost-update race

The excluded folders flow performs read/modify/write:

```text
read current excluded folders
modify local Set
write entire Set
```

Two rapid changes can read the same original value and then overwrite one another.

### Recommended fix

Perform the modification atomically inside one DataStore `edit { ... }` operation.

Do not read the set outside the atomic update.

---

# 17. Play-count double recording and incorrect listening duration

## Location

`MusicService.kt:69-82`, `:199-200`, `:225-226`

The audit found multiple flush paths:

- `STATE_ENDED`;
- auto-transition callback;
- `onDestroy`.

`trackedSongId` is not cleared after a flush.

### Consequence

A single playback can be counted multiple times.

Also:

```text
msPlayed = elapsedRealtime(...)
```

means time spent paused can be counted as listening time.

This is particularly important because the app's statistics depend on accurate play events.

### Recommended fix

Design play-event tracking as an explicit state machine.

At minimum:
- make recording idempotent per track transition;
- clear/invalidate the tracked event after flush;
- ensure only one terminal path records completion;
- calculate active listening time rather than wall-clock elapsed time if paused time should not count;
- add regression tests for:
  - normal completion;
  - auto-transition;
  - pause/resume;
  - service destruction;
  - repeated end callbacks.

---

# 18. Stats stale-query race

## Location

`ui/screens/stats/StatsViewModel.kt:56-79`

Every range/category selection launches another coroutine.

Example:

```text
select WEEK
select ALL_TIME
```

If the WEEK query completes after ALL_TIME, it can overwrite the state.

The UI can then display:

```text
Header: ALL TIME
Chart/data: WEEK
```

### Recommended fix

Track the active load job:

```kotlin
private var loadJob: Job? = null

private fun load(...) {
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
        ...
    }
}
```

Alternatively model selection as a Flow and use `flatMapLatest`.

---

# 19. Search screen dead callback

## Location

`ui/screens/search/SearchScreen.kt`

The screen declares `onSongClick`, but the result click path directly calls:

```kotlin
viewModel.playSong(song, results)
```

instead.

The navigation graph supplies a callback intended to:
- play the song;
- open the player.

But that callback never fires.

Because Search is in `hideChromeRoutes`, the MiniPlayer is also hidden.

### User impact

Search result taps can start playback with no visible navigation/feedback.

### Recommended fix

Call the supplied `onSongClick(song, results)` from the result item.

Then remove unused playback injection from `SearchViewModel` if no longer needed.

---

# 20. Now Playing drag performance

## Location

`NowPlayingScreen.kt:135,161`

Current behavior launches a new coroutine for each drag event:

```kotlin
coroutineScope.launch {
    offsetY.snapTo(newOffset)
}
```

`Animatable` uses a `MutatorMutex`, so concurrent updates can cancel/reorder each other.

A high-refresh-rate device can generate roughly 120 pointer events/sec.

### Recommended fix

Prefer:
- `Modifier.draggable`;
- `anchoredDraggable`;
- or a single long-lived coroutine fed by a Channel.

Avoid one coroutine per pointer event.

---

# 21. Upload can report success for a zero-byte file

## Location

`UploadViewModel.kt:58-67`

The upload RequestBody reopens the stream.

If the file is deleted/revoked between the preflight and actual upload:

```kotlin
openInputStream(...) == null
```

the current `?.use` path can become a no-op.

The server may receive an empty part and return 2xx.

The UI then reports success.

### Recommended fix

Throw an `IOException` when the stream cannot be opened during `writeTo`.

---

# 22. Album grouping and lookup normalization mismatch

## Locations

- `MusicRepository.kt:121`
- `SongDao.kt:34`

Grouping uses:

```text
trim().lowercase()
```

but lookup uses:

```text
album = :albumTitle COLLATE NOCASE
```

This is not equivalent because the query does not trim.

Example:

```text
"Abbey Road"
"Abbey Road "
```

can be grouped together but later queried separately.

### User impact

Some tracks can disappear from the album detail view.

### Recommended fix

Normalize album data at write time, or make the query normalization match the grouping normalization, e.g. using trimming.

Prefer a single canonical normalization policy throughout the data layer.

---

# 23. `remember(key)` can discard user text edits

## Locations

- `SettingsScreen.kt:493-495`
- `GapFinderScreen.kt:34`

State is keyed on incoming preference values.

When SAVE changes preferences, the key changes and Compose can recreate the remembered state.

### User impact

The user can edit multiple fields, save one action, and have other unsaved edits reset.

### Recommended fix

Seed state once:

```kotlin
remember { ... }
```

and synchronize external preference changes with `LaunchedEffect`, or move editable state into the ViewModel.

---

# 24. Home has an unbounded 60fps scroll loop

## Location

`HomeScreen.kt:101-113`

Current pattern:

```kotlin
while (isActive) {
    listState.scrollBy(1f)
    delay(16)
}
```

This runs continuously while Home is composed.

### Problems

- approximately 60 iterations/sec;
- continues indefinitely;
- keeps CPU active;
- can fight user interaction;
- can cause unnecessary recomposition/work.

### Recommended fix

Either:
- remove automatic scrolling;
- make it user-enabled;
- pause it while the user is interacting;
- or replace it with a proper animation mechanism.

---

# 25. AudioMonitorCard animation bug

## Location

`AudioMonitorCard.kt:128-129`

Current animation specs contain:

```kotlin
durationMillis = (400..800).random()
delayMillis = (0..200).random()
```

These are evaluated during composition.

### Why this is a real bug

Every recomposition can create new animation specs.

The infinite transition can therefore restart/churn continuously.

Because the animation drives `heightPercent`, this creates a feedback pattern:

```text
frame
 -> animated state changes
 -> recomposition
 -> random spec changes
 -> animation restarts
 -> next frame
```

The card has 12 bars, so each can obtain a different spec.

### Recommended fix

Hoist the randomized specification:

```kotlin
remember(barCount) {
    ...
}
```

The exact implementation should preserve the intended visual randomness while making the spec stable across recompositions.

---

# 26. AudioMonitorCard / PlaybackArtStyles animations continue while paused

## AudioMonitorCard

The infinite transition remains active even when `isPlaying` is false.

The output is merely masked.

Therefore the card can continue producing animation/recomposition work at roughly 60fps while visually appearing paused.

### Recommended fix

Actually stop/freeze the animation when paused rather than only masking the displayed value.

---

# 27. PlaybackArtStyles `tween(0)` issue

## Location

`PlaybackArtStyles.kt:39`

Current pattern:

```kotlin
tween(
    if (isPlaying) 3000 else 0,
    easing = LinearEasing
)
```

The audit verified through bytecode inspection that `TweenSpec` accepts zero duration.

With an infinite repeat, a zero-duration animation can spin as fast as the frame clock permits.

### Recommended fix

Do not use `tween(0)` as a pause mechanism.

Instead:
- only create/run the animation while playing;
- otherwise use a frozen value such as `0f`.

---

# 28. Animation values read in Compose instead of draw phase

## Location

`PlaybackArtStyles.kt:34-43`

Animated values are read into composition and passed into child composables such as:
- CassettePlayer;
- ReelToReel;
- VinylRecord;
- VhsTape.

This invalidates more of the Compose subtree than necessary, including text nodes.

### Recommended fix

Where practical, read animation state inside a Canvas draw lambda / draw modifier so only the drawing layer is invalidated.

---

# 29. Chart hardening

The chart code is mostly already defensive.

## Verified safe

The following were explicitly checked and should not be "fixed" unnecessarily:

- `AreaChart.kt` guards `coords.first()/last()` with `points.size < 2`.
- `LineChart.kt` does the same.
- `PieChart.kt` protects against zero totals.
- `BlockSeekBar.kt` guards divisors.
- `ScatterPlot.kt` guards divisors.
- `BlockyBarChart.kt` guards its divisor/max cases.

## Real chart risks

### LineChart gridLines

`LineChart.kt:42` divides by `gridLines`.

If `gridLines == 0`:
- `0 / 0` can produce NaN;
- later values can produce Infinity.

Current callers are safe because the default is used, but the public component API is not defensive.

Recommended:

```kotlin
val safeGridLines = gridLines.coerceAtLeast(1)
```

### Negative chart values

Line/Area/Scatter use a max-value guard but do not define a proper negative-value coordinate system.

Negative values can render off-canvas.

Either:
- clamp data at the source if negative values are invalid;
- or explicitly support signed ranges in chart scaling.

### PieChart / BlockyBarChart negative values

Negative values can produce nonsensical negative sweep/geometry.

Define and enforce an input contract.

Do not silently allow invalid negative data if these components are only designed for non-negative values.

---

# 30. Navigation argument robustness

## Locations

- `PlaylistDetailViewModel.kt`
- `AlbumDetailViewModel.kt`
- `ArtistDetailViewModel.kt`

Patterns include:

```kotlin
checkNotNull(savedStateHandle["..."])
```

and:

```kotlin
PlaylistKind.valueOf(rawArg)
```

### Current state

The current navigation wiring supplies valid arguments, so these are not normal-path bugs.

### Why they remain risks

They can fail under:
- restored stale back stacks;
- malformed/deep links;
- future enum renames;
- navigation changes.

### Recommended fix

Parse defensively and expose an error/empty state instead of throwing from a ViewModel constructor.

For enums:

```kotlin
runCatching {
    PlaylistKind.valueOf(rawArg)
}.getOrNull()
```

---

# 31. DataStore failure handling

## Locations

- `UserPreferencesRepository.kt`
- `NetworkModule.kt`
- provider paths;
- playback paths;
- Manage Sources.

Raw DataStore reads do not consistently handle:
- `IOException`;
- `CorruptionException`.

### Consequence

A corrupt/unreadable preferences file can affect:
- settings;
- playback restoration;
- network requests;
- library synchronization.

### Recommended fix

Use DataStore's appropriate recovery mechanisms.

For example:
- explicit `IOException` recovery with defaults where appropriate;
- `ReplaceFileCorruptionHandler` for corruption where safe.

Do not silently hide non-recoverable failures.

---

# 32. Navidrome URL construction and credential handling

## Locations

- `NavidromeProvider.kt`
- `NetworkModule.kt`
- `SongArt.kt`

## Problems

URLs are constructed through string interpolation.

Potential issues:
- invalid/blank server URL;
- missing encoding of query values;
- server URL containing its own path/query;
- stale cached songs when credentials are cleared;
- invalid bitrate values;
- fallback to `localhost`.

### Important security problem

The password is currently appended as a query parameter.

BASIC HTTP logging is enabled.

Therefore credentials can potentially end up in logged URLs.

### Recommended fix

- validate server URL with `toHttpUrlOrNull()`;
- use `HttpUrl.Builder`;
- use `addQueryParameter()`;
- do not put plaintext passwords into URLs where avoidable;
- prefer token/header-based authentication if supported by the chosen protocol;
- disable URL/credential logging in release;
- reject invalid configuration instead of silently targeting localhost.

---

# 33. Coil artwork localhost fallback

## Location

`ui/components/SongArt.kt`

When no valid server is configured, the artwork URL can remain:

```text
http://localhost/...
```

On Android this means the device itself, not the server.

### Recommended fix

Return null/fallback artwork when no server configuration exists.

Do not issue a request to device localhost accidentally.

---

# 34. MediaMetadataRetriever resource leak and artwork OOM risk

## Location

`coil/LocalAudioArtFetcher.kt`

`MediaMetadataRetriever` is created but not reliably released.

### Problem

Repeated artwork requests can leak native resources.

Embedded artwork is also decoded without a strong upper bound.

Large embedded images can create memory pressure/OOM.

### Recommended fix

Use a guaranteed `release()` path.

Also:
- reject/limit oversized embedded images;
- use proper sampling/downscaling;
- avoid decoding unreasonably large artwork.

---

# 35. Database performance: missing indices

## Current situation

No `@Entity(indices = [...])` was found.

Important tables:
- `songs`
- `play_events`

### Consequences

Queries involving:
- observation of songs;
- artist/album/folder filtering;
- play-event date ranges;
- grouping;
- song lookups

can fall back to full table scans.

### Proposed indices from audit

For `songs`, consider indexes around:

```text
remoteId
artist
album
folderPath
```

For `play_events`:

```text
startedAtEpochMs
songId
```

The exact final index set should be validated against actual DAO queries and SQLite query plans rather than blindly adding every column.

---

# 36. Stats whole-table aggregation in Kotlin

## Location

`StatsRepository.kt:136-163`

`getSessionStats()` does:

```text
observeEventsSince(since).first()
```

and materializes all events in the requested period.

It then performs session/gap grouping in Kotlin.

For ALL_TIME this can mean loading the entire play-event table into memory and repeating the work for each category/range change.

### Recommended direction

Move as much filtering/aggregation as practical into SQL.

At minimum:
- index time-range columns;
- avoid repeatedly materializing the same large event set;
- consider SQL-side aggregation for simple statistics;
- retain Kotlin processing only where the session algorithm truly requires it.

---

# 37. Dispatchers.Main hop in 5-second MusicService ticker

## Location

`MusicService.kt:258`

The service runs an IO loop but uses:

```kotlin
withContext(Dispatchers.Main) {
    player.currentPosition
}
```

The audit notes that `currentPosition` is a plain field read.

### Recommended fix

Remove the unnecessary Main dispatcher hop if the player API guarantees the read is safe from the current context.

---

# 38. Destructive Room migrations

## Locations

- `di/DatabaseModule.kt`
- `TerminusDatabase.kt`
- current database version: 5

Current configuration uses:

```kotlin
fallbackToDestructiveMigration()
```

### Consequence

A future schema version bump can silently recreate the database.

That can delete:
- liked songs;
- playlists;
- play history.

### Recommended fix

Create real Room `Migration` objects.

Test migrations before shipping.

If destructive migration is retained for development, restrict it to debug/development behavior rather than production.

---

# 39. Production APK / build optimization

## Current finding

`app/build.gradle.kts` has minification disabled in both build types.

Release APK was reported around:

```text
55.5 MB
```

across four DEX files.

`material-icons-extended` appears to pull in many unused vector classes.

The audit also saw declarations for:
- `libs.guava`
- `androidx.security.crypto`

with no source usage found.

### Recommended steps

1. Enable R8/minification for release.
2. Confirm resource shrinking configuration.
3. Remove unused dependencies after verifying the full project.
4. Rebuild and measure APK/AAB size.
5. Check startup/performance impact after shrinking.

Do not remove a dependency based only on grep if it is used indirectly/configurationally; verify Gradle resolution and runtime use.

---

# 40. Provider failure isolation

`MusicRepository.syncLibrary()` currently aggregates provider results.

If one provider fails, the whole sync can fail.

Example:
- Navidrome unavailable;
- Local MediaStore still works;
- but the entire sync operation aborts.

### Recommended direction

Handle providers independently.

Conceptually:

```text
Local provider -> success
Navidrome      -> failure
---------------------------
Sync result:
  local updated
  Navidrome unavailable
```

Report per-provider status.

Do not allow a temporary remote failure to cause unrelated local data deletion.

Permanent configuration/authentication errors should not be retried forever.

---

# 41. `toggleLike()` is non-atomic

## Location

`MusicRepository.kt:140-161`

Current pattern:

```text
isCurrentlyLiked = query
newState = !isCurrentlyLiked
if (...) unlike()
else like()
```

Two concurrent toggles can observe the same state.

Also the local DB update happens before the remote provider call, while provider failures may be swallowed.

### Recommended fix

Use:
- an atomic SQL toggle;
- or serialize toggles per song ID.

Treat remote synchronization as a separate retryable operation rather than silently creating divergence.

---

# 42. Stats summary consistency

## Location

`StatsRepository.kt:77-86`

Multiple independent Room queries produce:
- totals;
- top artists;
- daily counts;
- hourly counts.

A play event inserted between queries can make these disagree.

### Recommended fix

Either:
- wrap summary queries in a Room transaction for snapshot consistency;
- or explicitly document/accept eventual consistency.

---

# 43. Findings explicitly verified as safe

Do not "fix" these merely because they look suspicious.

## UI / Compose

- Nested LazyColumn in Now Playing is bounded by a 300dp height.
- Other `items(key = ...)` sites were checked and considered safe.
- Home `getYourMix` uses `.distinct()`.
- Library grouping keys were checked.
- Album/artist/playlist list keys were checked.

## Charts

- Empty `first()/last()` accesses are guarded.
- Divisors are guarded in the relevant components.
- PieChart zero-total protection works.

## Nullability/casts

- No `!!` operators.
- No unchecked casts.
- Lyrics parsing uses safe casts.
- Metadata extras access is null-safe.

## Navigation

- Album route encoding correctly handles `/`, spaces, and the `+` round trip.
- Current route wiring supplies the expected arguments.

## MediaStore

- The permission gate prevents the particular pre-permission SecurityException path identified during audit.
- The remaining issue is column availability, not the permission gate.

## Other

- `importAudioFiles()` correctly treats null streams as failed copies.
- `queryDisplayName()` checks its cursor column.
- `SystemInfoCard` sentinel handling is safe.
- `TerminalNavIcon` uses an exhaustive `when`, giving a compile-time failure if the enum grows without handling.

---

# 44. Suggested implementation order

The combined audits suggest this order.

## Phase 1 — smallest high-impact crash fixes

### 1. Stats duplicate key
- add song identity to `TopCategoryItem`;
- key SONG rows by song ID;
- add a regression test with two same-title songs.

### 2. Sync exception safety
- HomeViewModel: catch non-cancellation failures;
- ManageSourcesViewModel: same;
- always reset `isSyncing` in `finally`;
- surface a useful error state.

### 3. Remove GlobalScope
- use `viewModelScope`;
- retain debounce Job;
- cancel safely.

---

# 45. Phase 2 — biggest performance problems

## 1. Remove `runBlocking` from NetworkModule

Replace DataStore-per-request access with cached state.

This should be one of the biggest measurable wins.

## 2. Remove `runBlocking` from MusicService resolver

Pre-resolve/cached configuration and avoid synchronous Room/DataStore chains inside the resolver.

## 3. Fix sync scheduling

Remove unnecessary cold-start duplicate sync.

Serialize sync.

## 4. Fix Home scroll loop

Remove or gate the perpetual 16ms loop.

## 5. Remove unnecessary Main dispatcher hop

Do not switch to Main just to read `currentPosition` if safe.

---

# 46. Phase 3 — playback and lifecycle correctness

1. Fix `PlaybackController.future.get()` handling.
2. Add disconnection/reconnection handling.
3. Fix play-event double-recording.
4. Fix pause-time accounting.
5. Ensure cancellation is never swallowed.
6. Fix Stats stale loads.
7. Fix Now Playing drag coroutine behavior.

---

# 47. Phase 4 — database/data integrity

1. Chunk liked-song queries.
2. Chunk playlist inserts.
3. Make playlist import transactional.
4. Deduplicate or redesign duplicate-track semantics.
5. Serialize `syncLibrary()`.
6. Make DB mutation phase transactional.
7. Namespace provider IDs.
8. Make Manage Sources DataStore updates atomic.
9. Fix album normalization.
10. Add real Room migrations.
11. Add appropriate indices.
12. Add play-event retention/pruning.

---

# 48. Phase 5 — security and network correctness

1. Stop placing plaintext passwords in URLs where possible.
2. Disable URL logging in release.
3. Validate server URLs.
4. Build URLs with `HttpUrl.Builder`.
5. Remove localhost fallback behavior.
6. Validate bitrate values.
7. Handle credentials-cleared/stale-song situations safely.

---

# 49. Phase 6 — UI/animation/performance hardening

1. Stabilize `AudioMonitorCard` random animation specs with `remember`.
2. Stop animations when paused rather than masking them.
3. Replace `tween(0)` pause behavior.
4. Move animation reads into draw phase where practical.
5. Harden LineChart `gridLines`.
6. Define negative-value chart behavior.
7. Fix PieChart/BlockyBarChart invalid negative input behavior.
8. Memoize repeated `SimpleDateFormat` creation.
9. Hoist `hideChromeRoutes` out of recomposition.

---

# 50. Testing strategy for the next agent

Do not make all changes in one giant patch.

After each phase or small batch:

## Build

Run the project's normal Gradle compile/test tasks.

## Regression tests to add

### Stats
- two different songs with the same title;
- SONG tab renders without duplicate-key crash.

### Sync
- Navidrome unreachable;
- local provider still works;
- sync failure resets UI state;
- two concurrent sync requests;
- provider failure cannot delete another provider's songs.

### Playlist
- duplicate M3U entries;
- very large playlist;
- failure halfway through insertion leaves no playlist.

### Playback
- service connects successfully;
- service connection fails;
- service disconnects;
- reconnect after service recreation;
- normal track end;
- auto-transition;
- pause/resume;
- service destruction.

### Cancellation
- cancel sync;
- cancel upload;
- cancel gap finder;
- cancel settings import;
- verify cancellation does not become a failure message/retry.

### DataStore
- corrupted preferences;
- missing preferences;
- concurrent excluded-folder changes.

### Database
- migration from current version to next version;
- verify likes/playlists/play history survive.

### Performance
Measure before/after:
- cold startup;
- HTTP request latency;
- artwork loading;
- library sync duration;
- memory during ALL_TIME stats;
- release APK/AAB size;
- CPU/battery impact of Home and playback animations.

---

# 51. Important architectural principle

The next agent should avoid treating every finding as an isolated bug.

Several findings have a common root cause.

## Root cause group A — blocking configuration access

```text
DataStore
   ↓
runBlocking
   ↓
OkHttp / ExoPlayer
```

Fixing configuration caching addresses multiple performance paths.

## Root cause group B — unstructured concurrency

```text
GlobalScope
runCatching
catch (Exception)
```

These cause:
- lifecycle leaks;
- swallowed cancellation;
- process-level failures;
- stale UI updates.

Fixing coroutine structure addresses many findings simultaneously.

## Root cause group C — sync architecture

```text
multiple callers
    ↓
syncLibrary()
    ↓
read → diff → upsert → delete
```

Needs:
- single-flight/Mutex;
- provider isolation;
- atomic DB mutation;
- careful deletion semantics.

## Root cause group D — identity vs display data

The duplicate Stats key and provider ID concerns are both examples of confusing:
- display labels;
- provider-local identifiers;
- database identity.

Identity should always be explicit.

---

# 52. What NOT to do

Do not:

- replace all `checkNotNull` calls blindly;
- add `!!` as a quick workaround;
- add `try/catch(Exception)` everywhere;
- use `runCatching` around suspending operations without rethrowing cancellation;
- put the entire network scan inside a Room transaction;
- remove all animations just because they were flagged;
- add every imaginable database index without checking query patterns;
- use display labels as Compose identity;
- silently clamp invalid business data without defining its semantics;
- replace a real migration with destructive migration;
- perform a huge refactor and then try to debug dozens of changes at once.

---

# 53. Final priority list

If the next agent needs a concise starting queue:

### P0 — immediate correctness/crashes

1. Fix Stats SONG duplicate LazyColumn key.
2. Protect Home sync.
3. Protect Manage Sources sync.
4. Remove `GlobalScope`.
5. Handle PlaybackController connection failure.
6. Add PlaybackController disconnection/reconnection.
7. Preserve `CancellationException`.

### P1 — major performance/data correctness

8. Remove `runBlocking` from OkHttp interceptor.
9. Remove `runBlocking` from ExoPlayer resolver.
10. Serialize `syncLibrary()`.
11. Make sync DB mutations transactional.
12. Remove duplicate cold-start sync.
13. Fix play-count double-recording.
14. Fix liked-song query chunking.
15. Make playlist import transactional.

### P2 — database and production reliability

16. Add real Room migrations.
17. Add appropriate DB indexes.
18. Fix provider ID namespacing.
19. Handle DataStore corruption/IO.
20. Fix MediaStore DATA handling.
21. Fix album normalization.
22. Fix stats stale-query race.

### P3 — UI/UX/performance hardening

23. Fix Search callback.
24. Fix Now Playing drag implementation.
25. Fix AudioMonitor animation spec churn.
26. Stop paused animations.
27. Fix `tween(0)`.
28. Fix Home auto-scroll loop.
29. Move animation reads to draw phase.
30. Harden chart APIs.

### P4 — release/security cleanup

31. Enable R8/minification for release.
32. Remove verified-unused dependencies.
33. Re-measure APK/AAB.
34. Stop credentials from appearing in URLs/logs.
35. Validate Navidrome URLs/configuration.
36. Fix artwork resource/size handling.

---

# 54. Final handoff instruction to the next coding agent

Treat this document as a **read-only audit result and implementation plan**.

Before modifying anything:

1. Re-open the relevant source files and verify the exact current code.
2. Do not assume line numbers remain identical.
3. Preserve existing architecture unless a finding specifically requires an architectural change.
4. Implement changes in small batches.
5. Compile/test after every batch.
6. Add regression tests for deterministic bugs.
7. Report:
   - files changed;
   - exact behavior fixed;
   - tests run;
   - remaining known issues.
8. Do not "fix" findings explicitly marked as verified safe unless new code changes make them relevant.
9. Preserve coroutine cancellation semantics.
10. Prioritize correctness and data integrity before cosmetic optimization.

The goal is not to make every static concern disappear. The goal is to make Terminus robust under:
- large libraries;
- unavailable Navidrome servers;
- concurrent syncs;
- lifecycle transitions;
- cancellation;
- duplicate song titles;
- large playlists;
- long-term play history;
- production database migrations;
- real Android devices and restored navigation state.
