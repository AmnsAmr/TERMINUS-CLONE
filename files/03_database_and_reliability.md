# Terminus Handoff — P2: Database & Production Reliability

> Read `00_START_HERE_index_and_ground_rules.md` first. Independent of
> files 01/02, though item 2 here (provider ID namespacing) touches the
> same `SongEntity`/sync code as file 02 — coordinate if working in
> parallel.

---

## 1. Destructive Room migrations

**Locations:** `di/DatabaseModule.kt`, `TerminusDatabase.kt` (current DB
version: 5)

Current config uses `fallbackToDestructiveMigration()`. A future schema
version bump can silently recreate the database, deleting liked songs,
playlists, and play history.

**Fix:** Create real Room `Migration` objects. Test migrations before
shipping. If destructive migration is kept for development convenience,
restrict it to debug builds only, never production.

**Regression test:** migration from current version to next version;
verify likes/playlists/play history all survive.

---

## 2. Provider identity collision

**Location:** `SongEntity.kt`

`remoteId` is currently the sole primary key. Local MediaStore IDs and
Navidrome IDs are not namespaced — if the same string value occurs in two
providers, one provider's row can overwrite the other under `REPLACE`
semantics.

**Fix:** Use provider-aware identity, e.g. a composite
`(providerId, remoteId)`, or another namespaced identity strategy. This
requires a real Room migration (see item 1) — do it carefully, since it's
a primary-key structure change.

---

## 3. Missing database indices

Important tables (`songs`, `play_events`) currently have no
`@Entity(indices = [...])`. Queries involving song observation,
artist/album/folder filtering, play-event date ranges, grouping, and song
lookups can fall back to full table scans.

**Proposed starting set** (validate against actual DAO queries and SQLite
query plans before committing — don't add indices blindly):
- `songs`: `remoteId`, `artist`, `album`, `folderPath`
- `play_events`: `startedAtEpochMs`, `songId`

---

## 4. Stats whole-table aggregation in Kotlin

**Location:** `StatsRepository.kt:136-163`

`getSessionStats()` calls `observeEventsSince(since).first()` and
materializes all events in the requested period, then does session/gap
grouping in Kotlin. For `ALL_TIME`, this can mean loading the entire
play-event table into memory, repeated on every category/range change.

**Fix direction:** move as much filtering/aggregation as practical into
SQL. At minimum: index time-range columns (see item 3), avoid repeatedly
materializing the same large event set, consider SQL-side aggregation for
simple statistics, and keep Kotlin processing only where the session
algorithm genuinely requires it.

**Measure:** memory during `ALL_TIME` stats, before and after.

---

## 5. Stats stale-query race

**Location:** `ui/screens/stats/StatsViewModel.kt:56-79`

Every range/category selection launches a new coroutine. If a user selects
WEEK then immediately ALL_TIME, and the WEEK query happens to complete
after the ALL_TIME one, it can overwrite state — the UI can end up showing
`Header: ALL TIME` with `Chart/data: WEEK`.

**Fix:**
```kotlin
private var loadJob: Job? = null

private fun load(...) {
    loadJob?.cancel()
    loadJob = viewModelScope.launch { ... }
}
```
Alternatively, model selection as a `Flow` and use `flatMapLatest`.

---

## 6. Stats summary consistency

**Location:** `StatsRepository.kt:77-86`

Multiple independent Room queries produce totals, top artists, daily
counts, and hourly counts. A play event inserted between queries can make
these disagree with each other.

**Fix:** either wrap summary queries in a Room transaction for snapshot
consistency, or explicitly document/accept eventual consistency (pick one
and be deliberate about it — don't leave it ambiguous).

---

## 7. `toggleLike()` is non-atomic

**Location:** `MusicRepository.kt:140-161`

Current pattern: read `isCurrentlyLiked` → compute `newState = !isCurrentlyLiked`
→ call `like()`/`unlike()`. Two concurrent toggles can observe the same
stale state. Also the local DB update happens before the remote provider
call, and provider failures may be silently swallowed.

**Fix:** use an atomic SQL toggle, or serialize toggles per song ID. Treat
remote synchronization as a separate, retryable operation rather than
letting local and remote state silently diverge.

---

## 8. MediaStore `DATA` column risk

**Locations:** `ManageSourcesViewModel.kt:57`, `LocalMediaProvider.kt:67`

Code uses `cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)`.
`DATA` is deprecated and its availability isn't guaranteed (app targets
SDK 34, min SDK 29). The permission gate reduces SecurityException risk
but doesn't guarantee the cursor contains this column.

**Fix:**
```kotlin
val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
if (dataCol < 0) {
    // handle unsupported/missing column
}
```
Apply this in both locations, with an explicit fallback/error state rather
than a crash.

---

## 9. Album grouping and lookup normalization mismatch

**Locations:** `MusicRepository.kt:121`, `SongDao.kt:34`

Grouping uses `trim().lowercase()`, but lookup uses
`album = :albumTitle COLLATE NOCASE` — which doesn't trim. `"Abbey Road"`
and `"Abbey Road "` can be grouped together but queried separately later,
causing tracks to disappear from the album detail view.

**Fix:** normalize album data at write time, or make query normalization
match grouping normalization (e.g. add trimming to the query). Prefer one
canonical normalization policy used consistently across the data layer.

---

## 10. DataStore failure handling

**Locations:** `UserPreferencesRepository.kt`, `NetworkModule.kt`, provider
paths, playback paths, Manage Sources.

Raw DataStore reads don't consistently handle `IOException` or
`CorruptionException`. A corrupt/unreadable preferences file can affect
settings, playback restoration, network requests, and library sync all at
once.

**Fix:** use DataStore's recovery mechanisms — explicit `IOException`
recovery with sensible defaults, and `ReplaceFileCorruptionHandler` where
safe. Do not silently hide non-recoverable failures; surface them where
the user can act on them.

**Regression tests:** corrupted preferences; missing preferences;
concurrent excluded-folder changes (see item 11).

---

## 11. Manage Sources lost-update race

The excluded folders flow does read → modify local Set → write entire Set.
Two rapid changes can read the same original value and overwrite each
other.

**Fix:** perform the modification atomically inside one DataStore
`edit { ... }` block. Don't read the set outside the atomic update.

---

## After this file: build & test checklist

- Run a migration test from the current DB version to the next; confirm
  likes/playlists/play history survive.
- Confirm album detail views no longer lose tracks due to trailing
  whitespace in album titles.
- Confirm rapid WEEK→ALL_TIME stats switching always shows matching
  header/data.
- Confirm corrupted preferences file doesn't take down settings, playback
  restore, network, or sync simultaneously.
- Confirm two rapid excluded-folder edits both persist (no lost update).

When done, move to `04_ui_animation_and_navigation.md`.
