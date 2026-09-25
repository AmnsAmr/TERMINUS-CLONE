# Terminus Handoff — P4: Security & Release Hardening

> Read `00_START_HERE_index_and_ground_rules.md` first. This file is safe
> to tackle last — it's about production hygiene rather than user-facing
> bugs, though item 1 (credentials in URLs/logs) is a genuine security
> issue and shouldn't be deprioritized indefinitely.

---

## 1. Navidrome URL construction and credential handling

**Locations:** `NavidromeProvider.kt`, `NetworkModule.kt`, `SongArt.kt`

URLs are built via string interpolation. Problems found:
- invalid/blank server URL isn't validated;
- query values aren't consistently encoded;
- server URL containing its own path/query isn't handled correctly;
- stale cached songs when credentials are cleared;
- invalid bitrate values aren't validated;
- fallback to `localhost` when no server is configured.

**Important security problem:** the password is currently appended as a
query parameter, and BASIC HTTP logging is enabled — so credentials can
end up in logged URLs.

**Fix:**
- validate the server URL with `toHttpUrlOrNull()`;
- build URLs with `HttpUrl.Builder` and `addQueryParameter()` (handles
  encoding correctly);
- don't put plaintext passwords into URLs where avoidable;
- prefer token/header-based authentication if the protocol supports it;
- disable URL/credential logging in release builds;
- reject invalid configuration outright instead of silently falling back
  to targeting `localhost`.

---

## 2. Coil artwork localhost fallback

**Location:** `ui/components/SongArt.kt`

When no valid server is configured, the artwork URL can remain
`http://localhost/...` — on Android this targets the device itself, not
the intended server.

**Fix:** return null/fallback artwork when no server configuration exists.
Don't issue a request to device localhost by accident.

---

## 3. `MediaMetadataRetriever` resource leak and artwork OOM risk

**Location:** `coil/LocalAudioArtFetcher.kt`

`MediaMetadataRetriever` is created but not reliably released. Repeated
artwork requests can leak native resources. Embedded artwork is also
decoded without a strong upper bound, so large embedded images can cause
memory pressure/OOM.

**Fix:**
- use a guaranteed `release()` path (e.g. `use {}` or try/finally);
- reject or limit oversized embedded images;
- use proper sampling/downscaling rather than decoding at full resolution.

---

## 4. Upload can report success for a zero-byte file

**Location:** `UploadViewModel.kt:58-67`

The upload `RequestBody` reopens the stream. If the file is
deleted/revoked between the preflight check and the actual upload,
`openInputStream(...) == null`, and the current `?.use` path can become a
silent no-op — the server may receive an empty part and return 2xx, and
the UI then reports success incorrectly.

**Fix:** throw an `IOException` when the stream can't be opened during
`writeTo`, so this surfaces as a real failure instead of a false success.

---

## 5. `Dispatchers.Main` hop in the 5-second MusicService ticker

**Location:** `MusicService.kt:258`

The service runs an IO loop but does:
```kotlin
withContext(Dispatchers.Main) {
    player.currentPosition
}
```
`currentPosition` is a plain field read — the Main dispatcher hop is
unnecessary overhead if the player API guarantees the read is safe from
the current context (verify this against the ExoPlayer/Media3 version in
use before removing).

**Fix:** remove the unnecessary Main dispatcher hop once verified safe.

---

## 6. Production APK / build optimization

**Location:** `app/build.gradle.kts`

Minification is currently disabled in both build types. Release APK was
measured around 55.5 MB across four DEX files.
`material-icons-extended` appears to pull in many unused vector classes.
`libs.guava` and `androidx.security.crypto` are declared with no source
usage found.

**Fix steps, in order:**
1. Enable R8/minification for release.
2. Confirm resource shrinking configuration.
3. Remove unused dependencies **after verifying full-project Gradle
   resolution and runtime use** — don't remove something based only on a
   grep hit; a dependency can be used indirectly/configurationally.
4. Rebuild and measure APK/AAB size.
5. Check startup/performance impact after shrinking (R8 can occasionally
   change behavior around reflection-based code paths — retest playback
   and any reflection-adjacent features).

**Measure before/after:** release APK/AAB size.

---

## After this file: build & test checklist

- Confirm Navidrome credentials no longer appear in logs or logged URLs in
  a release build.
- Confirm artwork requests fail gracefully (no `localhost` request) when
  no server is configured.
- Confirm repeated artwork loads (e.g. scrolling a large library) don't
  leak native `MediaMetadataRetriever` resources or trigger OOM on large
  embedded art.
- Confirm an upload of a file deleted mid-flight surfaces as a failure,
  not a false success.
- Confirm release build size and startup time after enabling
  minification/shrinking, and re-verify playback still works correctly
  post-shrink.

This is the last file in the sequence — once this and the regression
suite in the other files pass, report back per the checklist in
`00_START_HERE_index_and_ground_rules.md`.
