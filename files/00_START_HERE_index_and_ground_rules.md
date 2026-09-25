# Terminus Audit — Handoff Index & Ground Rules

This audit was split into separate task files so no single agent session is
overwhelmed. **Every agent, regardless of which file they're assigned, must
read this file first.**

## Files in this handoff set

| File | Scope | Priority |
|---|---|---|
| `01_crash_fixes.md` | Stats crash, unhandled sync exceptions, GlobalScope, PlaybackController connection handling, swallowed cancellation | P0 — do first |
| `02_performance_and_sync_architecture.md` | `runBlocking` removal, `syncLibrary()` serialization/atomicity, play-count double-recording, liked-song chunking, playlist import transaction | P1 |
| `03_database_and_reliability.md` | Room migrations, indices, provider ID namespacing, DataStore corruption handling, MediaStore DATA column, album normalization, stats stale-query race | P2 |
| `04_ui_animation_and_navigation.md` | Search callback bug, Now Playing drag perf, AudioMonitor/PlaybackArtStyles animation bugs, Home scroll loop, chart hardening, navigation argument robustness | P3 |
| `05_security_and_release_hardening.md` | Credentials in URLs/logs, Navidrome URL validation, artwork resource leak/OOM, R8/minification, unused deps, APK size | P4 |
| `06_verified_safe_do_not_touch.md` | Things that look suspicious but were confirmed safe — do not "fix" these | Reference only |

Work through them roughly in priority order (01 → 05), but each file is
independently actionable — a different agent/session can pick up any one
file without needing the others, as long as they've read this file.

## Non-negotiable process rules (apply to every file)

1. **Re-verify before editing.** Re-open the relevant source files and
   confirm the exact current code and line numbers. Do not assume line
   numbers in these files are still accurate — the codebase may have moved.
2. **Small batches.** Make changes incrementally, not as one giant patch.
3. **Compile + test after every batch.** Run the project's normal Gradle
   compile/test tasks before moving to the next item.
4. **Add regression tests** for deterministic bugs you fix (each phase file
   lists relevant tests).
5. **Preserve coroutine cancellation semantics** — `CancellationException`
   must always be rethrown, never swallowed.
6. **Report back** after each batch: files changed, exact behavior fixed,
   tests run, remaining known issues.
7. **Do not "fix" anything listed in `06_verified_safe_do_not_touch.md`**
   unless your own changes make it newly relevant.
8. **No broad rewrites.** Preserve existing architecture unless a finding
   specifically requires a structural change. The goal is a robust,
   incrementally-improved codebase — not a rewrite.

## Things to never do (applies everywhere)

- Don't replace all `checkNotNull` calls blindly.
- Don't add `!!` as a quick workaround.
- Don't add `try/catch(Exception)` everywhere.
- Don't use `runCatching` around suspending operations without rethrowing
  `CancellationException`.
- Don't put an entire network scan inside a Room transaction.
- Don't remove all animations just because they were flagged.
- Don't add every imaginable database index without checking query patterns.
- Don't use display labels as Compose identity.
- Don't silently clamp invalid business data without defining its semantics.
- Don't replace a real migration with a destructive migration.

## Root causes worth knowing (context, not a task list)

A few findings across different files share one underlying cause. Knowing
this helps you fix things at the right layer instead of patching symptoms:

- **Blocking configuration access:** `DataStore → runBlocking → OkHttp /
  ExoPlayer`. Fixing configuration caching (file 02) fixes multiple
  performance issues at once.
- **Unstructured concurrency:** `GlobalScope`, `runCatching`, and bare
  `catch (Exception)` around suspending code. Fixing coroutine structure
  (file 01) addresses many findings simultaneously.
- **Sync architecture:** multiple callers hit `syncLibrary()`'s
  read → diff → upsert → delete flow with no single-flight guard or
  transaction (file 02).
- **Identity vs. display data:** confusing display labels, provider-local
  IDs, and database identity (files 01 and 03). Identity should always be
  explicit.

## Codebase baseline (already confirmed true — don't re-audit this)

- Zero `!!` operators in app source.
- No unchecked casts in app source.
- Audit covered all 87 Kotlin files under `app/src/main/java` plus a
  dedicated 47-file UI pass, manifest, and Gradle config.
- The audit was read-only; no source files were modified.

## Overall goal

Make Terminus robust under: large libraries, unavailable Navidrome servers,
concurrent syncs, lifecycle transitions, cancellation, duplicate song
titles, large playlists, long-term play history, production database
migrations, and real Android devices with restored navigation state.

The goal is not to make every static-analysis concern disappear — it's to
fix the confirmed, reachable problems without introducing new risk.
