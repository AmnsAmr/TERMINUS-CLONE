# Terminus Handoff — P1: Performance & Sync Architecture

> Read `00_START_HERE_index_and_ground_rules.md` first. Assumes `01_crash_fixes.md`
> is already done (or is being done in parallel by a different session) —
> this file doesn't depend on it, but P0 items should land first if possible.

This is the single biggest "why does the app feel heavy / why does sync
behave badly" cluster. Most of it traces back to two root causes: blocking
configuration reads, and a `syncLibrary()` that isn't safe under
concurrency.

---

## 1. `runBlocking` on every HTTP request (highest-priority perf issue)

**Location:** `di/NetworkModule.kt:41`

The OkHttp interceptor calls approximately:
```kotlin
runBlocking {
    prefsRepo.preferences.first()
}
```
to get server URL/credentials. This is a disk-backed DataStore read
executed on an OkHttp worker thread for **every single HTTP request**,
including frequent ones like Coil artwork loads. Consequences: worker
thread blocking, repeated DataStore reads, serialization around
DataStore's single-file lock, unnecessary latency and thread pressure. This
was identified as the single largest source of latency/thread pressure in
the whole app.

**Fix:** Replace the per-request DataStore read with a cached
configuration mechanism — e.g. a `StateFlow` holding current server
config, or a carefully managed `@Volatile`/atomic cached
credentials/configuration field. The interceptor should consume an
already-resolved value, not synchronously read DataStore.
**Do not simply move the same `runBlocking` somewhere else** — that's not
a fix.

---

## 2. `runBlocking` inside the ExoPlayer data-source resolver

**Location:** `playback/MusicService.kt:93`

Currently approximately:
```kotlin
runBlocking {
    musicRepository.getSongUri(songId)
}
```
The repository path does a Room `getById` plus another preferences read.
The ExoPlayer resolver is a synchronous loading path — blocking it
introduces thread blocking, unnecessary DB/preferences latency, and
potential deadlock risk depending on dispatcher/thread interactions.

**Fix:** Design the resolver around already-cached song/server
configuration, or resolve the playback URI before the synchronous resolver
is invoked. Key requirement: **the synchronous resolver must not perform a
blocking coroutine chain involving Room + DataStore.** Treat this as a
separate fix from item 1 — don't conflate the two caches unless you've
verified they should share one.

---

## 3. Duplicate cold-start sync scheduling

**Location:** `TerminusApplication.kt:37-50`

`onCreate` enqueues both a 12-hour periodic worker **and** a one-shot
worker using `ExistingWorkPolicy.REPLACE`. Each sync can do a full
MediaStore query, a Navidrome `search3` request with `songCount = 10000`,
load the entire songs table into memory, diff, upsert, and delete
(`NavidromeProvider.kt:34-40`, `MusicRepository.kt:76-99`). A cold start
can trigger a full sync unnecessarily, and the dual scheduling can create
overlapping sync concerns.

**Fix:** Remove the cold-start `REPLACE` one-shot if it isn't actually
required. Keep sync on explicit refresh / controlled WorkManager
scheduling. This matters less once item 4 below makes `syncLibrary()`
safe under concurrent invocation, but removing the redundant trigger is
still worth doing on its own.

---

## 4. `syncLibrary()` is not serialized or atomic

**Location:** `MusicRepository.kt:47-99`

Current conceptual flow: scan providers → read all existing songs →
compute additions/updates/deletes → upsert → delete. There is no
Mutex/single-flight guard and no transaction covering the DB mutation
sequence.

**Known callers:** Home refresh, Manage Sources, WorkManager, import
completion.

**Concrete race:**
```text
Sync A reads DB
Sync B inserts new songs
Sync A computes deletes from stale snapshot
Sync A deletes songs that B just inserted
```
Also, upsert happens before delete — if the process dies between these
operations, the DB can end up partially updated/stale.

**Fix:**
```kotlin
private val syncMutex = Mutex()

suspend fun syncLibrary() = syncMutex.withLock {
    ...
}
```
Then make the DB mutation phase (diff → upsert → delete) atomic with a
Room transaction. Important: **the provider network scan itself does not
need to be inside the transaction.** Prefer this order:
1. scan providers outside the transaction;
2. acquire the mutex;
3. perform diff and DB mutation atomically inside a transaction.

Also make sure one failed/partial provider scan cannot cause deletion of
another provider's data — see the provider-failure-isolation note below
(this is closely related but tracked separately in item 6).

**Regression tests:** two concurrent sync requests; provider failure
cannot delete another provider's songs; sync failure resets UI state
(cross-check with `01_crash_fixes.md` item 2/3); Navidrome unreachable
while local provider still works.

---

## 5. Provider failure isolation in `syncLibrary()`

`MusicRepository.syncLibrary()` currently aggregates provider results — if
one provider fails, the whole sync can fail. Example: Navidrome
unavailable, local MediaStore still works, but the entire sync operation
aborts.

**Fix direction:** handle providers independently and report per-provider
status:
```text
Local provider -> success
Navidrome      -> failure
---------------------------
Sync result:
  local updated
  Navidrome unavailable
```
Do not let a temporary remote failure cause unrelated local data deletion.
Permanent configuration/authentication errors should not be retried
forever (distinguish "unreachable right now" from "misconfigured").

This is naturally implemented alongside item 4's transactional rewrite —
do them together if practical, since both touch the same code path.

---

## 6. Play-count double-recording and incorrect listening duration

**Location:** `MusicService.kt:69-82`, `:199-200`, `:225-226`

Multiple flush paths exist: `STATE_ENDED`, the auto-transition callback,
and `onDestroy`. `trackedSongId` is not cleared after a flush, so a single
playback can be counted multiple times. Also, `msPlayed` is computed from
`elapsedRealtime(...)`, meaning time spent paused counts as listening time.
This directly corrupts the stats feature's data.

**Fix:** Design play-event tracking as an explicit state machine. At
minimum:
- make recording idempotent per track transition;
- clear/invalidate the tracked event after flush;
- ensure only one terminal path records completion;
- calculate active listening time rather than wall-clock elapsed time (so
  paused time doesn't count);
- add regression tests for: normal completion, auto-transition,
  pause/resume, service destruction, repeated end callbacks.

---

## 7. Unbounded SQL `IN (:ids)` query for liked songs

**Locations:** `MusicRepository.kt:226-230`, `SongDao.kt:50-51`

```kotlin
@Query("SELECT * FROM songs WHERE remoteId IN (:ids)")
suspend fun getByIds(ids: List<String>): List<SongEntity>
```
Unlike other paths in `MusicRepository`, this isn't chunked. Large liked
libraries can hit SQLite's bound-variable limit and throw
`SQLiteException: too many SQL variables`.

**Fix:** Chunk IDs into safe batches (roughly 500–900 per query). Longer
term, consider a paginated repository API instead of loading the entire
liked library at once.

---

## 8. Playlist import is not transactional

**Location:** `MusicRepository.kt:334-340`

Current sequence: insert playlist row, then insert playlist-song rows. If
the second step fails, the playlist row remains (orphaned/empty playlist).
Failure modes: duplicate M3U entries can violate the composite primary key
`(playlistId, songId)`; large playlists can hit SQL bind-variable limits;
insertion failure can leave an empty or partially populated playlist.

**Fix:** Create a DAO-level `@Transaction` operation that inserts the
playlist, inserts all playlist-song rows, and rolls back everything if any
step fails. Also decide whether duplicate M3U entries are intentional — if
not, deduplicate; if they should be allowed, the schema needs an
ordinal/position identity instead of relying on `(playlistId, songId)`
alone. Chunk large inserts to avoid the bind-variable limit.

**Regression tests:** duplicate M3U entries; very large playlist; failure
halfway through insertion leaves no playlist (not a partial one).

---

## After this file: build & test checklist

- Measure before/after: HTTP request latency, artwork loading, cold
  startup, library sync duration.
- Confirm two concurrent syncs no longer race, and a Navidrome-only
  failure doesn't delete local-provider songs.
- Confirm a single playback is recorded exactly once, and paused time
  isn't counted as listening time.
- Confirm a liked-song library well above ~900 songs doesn't throw a
  SQLite variable-limit error.
- Confirm a playlist import that fails partway leaves no orphaned
  playlist row.

When done, move to `03_database_and_reliability.md`.
