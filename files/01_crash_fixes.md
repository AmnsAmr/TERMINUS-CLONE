# Terminus Handoff — P0: Crash Fixes

> Read `00_START_HERE_index_and_ground_rules.md` first for process rules and
> things to never do. This file is self-contained for the P0 crash-fix work.

These are the highest-priority items: confirmed, reachable crashes and
lifecycle bugs that can kill the app or leave it in a dead state.

---

## 1. Hard crash: duplicate `LazyColumn` key in Stats SONG tab

**Location:** `ui/screens/stats/StatsScreen.kt:227`

Current pattern:
```kotlin
items(state.topCategoryItems, key = { it.label }) { ... }
```
For the SONG category, `label` is the song title. Two distinct songs can
share a title (e.g. two different tracks both called "Intro"). The DB
correctly treats them as different songs, but Compose sees the same key
twice and throws:
```
IllegalArgumentException: Key "Intro" was already used
```
The default Stats tab is ARTIST, so this is only exposed when the user taps
SONG — that's why it's easy to have missed in casual testing.

**Data chain:** `MusicRepository` (SongEntity uses `remoteId` as identity)
→ `PlayEventDao` groups by `songId` → query projects song title →
`StatsRepository` creates `TopCategoryItem(label = title)` → `StatsScreen`
uses title as the Compose key.

**Fix:** Carry identity through the model instead of using the display
label as identity:
```kotlin
data class TopCategoryItem(
    val label: String,
    val playCount: Int,
    val id: String
)
```
```kotlin
items(state.topCategoryItems, key = { it.id })
```
Do not use display text as Compose identity — this is the cleanest fix,
not a workaround.

**Regression test:** two different songs with the same title; SONG tab
renders without a duplicate-key crash.

---

## 2. Unhandled sync exceptions from Home ViewModel

**Location:** `HomeViewModel.kt:50-56`

Home starts library sync via a bare coroutine launch. If `syncLibrary()`
throws, the exception can reach the default uncaught exception handler and
crash the app. `NavidromeProvider.kt:42-44` explicitly throws when the
Subsonic response isn't OK — so a configured-but-unreachable Navidrome
server can crash the app on Home startup. Also, `isSyncing` isn't
guaranteed to reset on failure, which can leave the UI stuck.

**Fix:**
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
Do not blindly wrap this in `runCatching` — cancellation must still be
rethrown explicitly.

---

## 3. Manage Sources uses `GlobalScope`

**Location:** `ManageSourcesViewModel.kt:71,84-87`

Current behavior is approximately:
```kotlin
syncJob = GlobalScope.launch(Dispatchers.IO) {
    delay(1000)
    musicRepository.syncLibrary()
}
```
Problems: not tied to ViewModel lifecycle, can keep running after the
screen is destroyed, a sync exception can become an uncaught process-level
exception, and the debounce job handling isn't lifecycle-safe.

**Fix:** Use `viewModelScope.launch(Dispatchers.IO) { ... }`, cancel/replace
the previous job on re-trigger, and apply the same catch pattern as item 2
above (catch non-cancellation exceptions, rethrow `CancellationException`).
`viewModelScope` is itself lifecycle-bound, but if you store the job for
manual cancellation elsewhere, cancel it explicitly in `onCleared()` too.

---

## 4. PlaybackController connection failure & no reconnection handling

**Location:** `playback/PlaybackController.kt:100-104`

The code uses a future and calls `future.get()` from a listener running on
`MoreExecutors.directExecutor()`. Problems:
- `ExecutionException` / cancellation can escape unhandled.
- `onReady()` never runs on failure.
- No explicit connection-error state is exposed to the UI.

**More serious lifecycle problem:** there is no `onDisconnected` handling.
After `MusicService.stopSelf()` → `MusicService.onDestroy()`, the retained
controller can remain in a state where `connect()` short-circuits because
the controller field is non-null, even though the underlying service is
gone. **Result: playback can remain dead until process death.**

**Fix:**
- Handle future failure explicitly (don't let `ExecutionException` escape
  uncaught).
- Clear/reset the controller field when disconnected.
- Provide reconnection logic so `connect()` doesn't short-circuit on a
  stale non-null reference.
- Restore the interrupt flag if catching `InterruptedException`.
- Expose a recoverable connection state to the UI layer.

**Regression tests:** service connects successfully; service connection
fails; service disconnects; reconnect after service recreation; normal
track end; auto-transition; pause/resume; service destruction.

---

## 5. `CancellationException` is being swallowed across the codebase

Broad patterns like `catch (e: Exception) { ... }` and `runCatching { ... }`
around suspending operations are unsafe: `CancellationException` **is** an
`Exception`, and `runCatching` catches `Throwable`.

**Confirmed affected paths:**
- `LibrarySyncWorker.kt`
- `GapFinderViewModel.kt`
- `UploadViewModel.kt`
- `SettingsViewModel.kt`
- several `MusicRepository.kt` paths
- `NavidromeProvider.kt`
- Manage Sources-related operations

**Consequence:** cancelled work can turn into a failure UI message, a retry
result, or continued work after the caller is already gone. For
WorkManager specifically, cancellation can incorrectly become
`Result.retry()`.

**Fix pattern to apply everywhere on this list:**
```kotlin
try {
    ...
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

**Regression tests:** cancel sync; cancel upload; cancel gap finder; cancel
settings import — in each case, verify cancellation does not turn into a
failure message or retry.

---

## After this file: build & test checklist

- Run the project's normal Gradle compile/test tasks.
- Confirm the Stats SONG tab regression test passes with two same-titled
  songs.
- Confirm Home and Manage Sources both survive an unreachable Navidrome
  server without crashing, and `isSyncing`/loading state resets correctly.
- Confirm playback reconnects correctly after the service is recreated.
- Confirm cancelling sync/upload/gap-finder/settings-import does not
  surface as an error or trigger a retry.

When done, move to `02_performance_and_sync_architecture.md`.
