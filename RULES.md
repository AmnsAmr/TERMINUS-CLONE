# Terminus Coding Rules

These rules apply to every coding agent changing this repository. The goal is to improve Terminus without breaking existing behavior, losing user data, or making the app feel slower or less responsive.

## Before changing code

1. Read this file and the relevant source, callers, tests, and data flow before editing. Check `files/00_START_HERE_index_and_ground_rules.md` for the audit workflow and `files/06_verified_safe_do_not_touch.md` for findings already checked and ruled safe.
2. Re-verify audit findings against the current code. Handoff notes can become stale after fixes; do not blindly repeat or revert their old code examples.
3. State the user-visible behavior and the smallest code path that needs to change. Check related screens, navigation, playback, sync, preferences, and persisted data that depend on that path.
4. Keep changes small and focused. Preserve the current architecture unless a concrete problem requires changing it. Do not combine unrelated cleanup, formatting, renaming, or refactoring with a bug fix.
5. Do not edit generated output, signing material, or unrelated untracked/user files. Do not overwrite release artifacts that were already present in the workspace.

## Implementation and verification

- Compile and run the relevant tests after each cohesive change batch. Add a regression test for deterministic bugs. Use instrumentation tests for Android/Room behavior when unit tests cannot cover it; report if they compile but cannot run on an available device.
- Run `git diff --check` and inspect the final diff. Report what changed, the checks that ran, checks that could not run, and remaining risks. Never claim device behavior was verified by a compile-only check.
- Do not add a dependency until checking whether the platform, existing libraries, or current architecture already provide the capability. Confirm source/API usage and resolve the relevant Gradle configuration before removing a dependency. Keep necessary indirect or direct API dependencies.
- Keep public interfaces and persisted formats stable unless the task requires a change. If database schema or identity changes, write a real migration and test data survival. Never use destructive migration as a production shortcut.
- Prefer clear, explicit behavior over broad defensive scaffolding. Do not add `!!`, unchecked casts, blanket `catch (Exception)`, silent fallbacks, or broad rewrites to make a compiler or test pass.

## Coroutine, lifecycle, and data rules

- Never use `runBlocking` in UI, playback, request interceptors, or other latency-sensitive paths. Do not replace one blocking read with another. Cache configuration as observable state where synchronous consumers need it.
- Tie UI work to `viewModelScope` or a composable effect and service work to a service-owned scope. Cancel obsolete jobs and stop work when its owner is cleared. Do not use `GlobalScope`.
- Always rethrow `CancellationException` before handling other failures. A cancelled request must not become a success, retry, or user-facing error unless the caller explicitly defines that behavior.
- Keep Room/network/file work off the Main thread. Use suspending APIs and the appropriate dispatcher. Keep transactions short and limited to related database operations; never hold a Room transaction open during a network scan or file import.
- Make multi-step database changes atomic when partial completion would corrupt state. Preserve liked songs, playlists, play history, and references through schema changes. Chunk large SQL `IN` lists and bulk inserts below SQLite bind limits.
- Treat provider IDs and database IDs as different concepts. Preserve provider-aware identity through joins, queues, likes, history, and navigation. Do not use titles or other display text as identity.
- Make retries and remote synchronization explicit. Preserve valid cached provider rows after a failed scan; do not interpret a failed or unconfigured provider as an empty successful scan unless clearing that provider's cache is intended.
- Use DataStore's atomic `edit {}` for read/modify/write operations. Expose preferences through the existing repository/Flow rather than constructing DataStore access in UI components.

## Performance and smoothness

Terminus should remain responsive while browsing large libraries and playing audio. Performance changes must address measured work, not just suspicious-looking syntax.

### Find the bottleneck first

- When possible, capture a baseline before changing performance-sensitive code: Android Studio CPU/system trace, Compose recomposition/layout inspection, memory allocation profiling, and frame timing during a repeatable scroll or playback scenario. Use Macrobenchmark/Perfetto when suitable infrastructure exists.
- Repeat the same scenario after the change on the same device/build conditions. Record the metric that improved (for example, UI frame duration, missed frames, allocations, startup time, query time, or memory). Do not claim a performance gain from code inspection alone.
- If no device, profiler, or baseline is available, make only a clearly justified low-risk change and say that its performance impact remains unmeasured.
- A 60 Hz display has about 16.7 ms per frame; a 120 Hz display has about 8.3 ms. Avoid spending most of that budget in composition, layout, drawing, or Main-thread callbacks.

### Keep the Main/UI thread light

- Composables render state and dispatch events. Do not run database queries, network calls, blocking file access, large parsing, full-library grouping/sorting, or expensive image processing on Main.
- Remember that `viewModelScope.launch {}` starts on Main by default. Put CPU-heavy transformations on `Dispatchers.Default`, blocking I/O on `Dispatchers.IO`, or move filtering/ordering into SQL when that is the measured bottleneck.
- Keep work in list item composition cheap. Use `LazyColumn`/`LazyRow` for potentially large collections, stable unique keys, and avoid recomputing whole-list transformations inside each item. Do not load artwork or perform file/network work synchronously during composition or scroll.
- Avoid repeated allocations in hot paths such as frame callbacks, pointer handlers, list item composition, and animation loops. Hoist immutable specs and reusable calculations with stable `remember` keys where appropriate; do not cache values whose inputs can change.
- Reduce data before sending it to UI. Query only the columns/rows needed, paginate very large collections where product behavior permits, and avoid materializing the same large dataset repeatedly. Keep necessary session grouping or other domain work correct; do not move aggregation to SQL unless the result semantics remain equivalent.

### Compose state and recomposition

- Collect long-lived flows with lifecycle-aware APIs. Expose state from ViewModels/repositories and avoid starting duplicate collectors or queries during recomposition.
- Keep frequently changing state reads close to the UI element that needs them. A value updated every frame should not invalidate a large screen subtree; isolate it in a small composable or read it during drawing where practical.
- Use `derivedStateOf` only when it avoids meaningful recomputation from rapidly changing inputs. Use `remember` for expensive calculations with correct keys. Avoid unstable parameters or creating new lambdas, collections, formatters, and animation specs on every composition when they cause measured churn.
- Do not use display labels as lazy-list keys. Preserve existing identity-safe keys and regression tests.
- Do not use side effects for work that belongs in a ViewModel/repository, and do not trigger navigation, database writes, or network operations as a consequence of ordinary recomposition.

### Polling and continuous work

- Every loop, ticker, and frame-clock effect needs an owner, a clear stop condition, and a documented cadence justified by the behavior it serves. Cancel it when playback stops, a feature is disabled, or the owning lifecycle ends.
- Avoid manual `delay(16)`/`while (isActive)` loops for visual motion when Compose animation or drawing APIs can express the target without waking the app continuously. Do not add an always-running Home/list auto-scroll or background animation.
- Poll only when event/listener-based updates are unavailable or unsuitable. Keep persistence polling infrequent and never use UI-frequency polling for a value that changes slowly.
- Do not launch a coroutine for every pointer event. Use gesture APIs (`draggable`, `anchoredDraggable`, or a single controlled event stream) and keep drag state updates ordered.
- Infinite animations should run only while visible, relevant, and enabled. Paused playback should not keep decorative animation clocks active. Never use `tween(0)` inside an infinite repeat as a pause mechanism.

### Animation and movement policy

- Preserve meaningful interaction feedback and playback correctness. Disable or reduce decorative motion without breaking seeking, drag gestures, focus, navigation, state communication, or audio crossfade behavior.
- Use stable animation specifications; do not randomize specs or create animation state in a way that restarts on every recomposition. Read rapidly changing values in a draw phase when it avoids invalidating text/layout unnecessarily.
- The current Settings/DataStore architecture can support a persistent `Full`, `Reduced`, and `Off` motion preference. If implementing it, store a small enum/string through the existing `UserPreferencesRepository`, expose it as normal observable UI state, and pass a stable motion policy to affected components. Do not read DataStore from a composable animation loop or on every frame.
- Define the levels consistently: `Full` allows existing optional motion; `Reduced` disables decorative infinite motion and shortens/minimizes optional transitions while retaining useful state feedback; `Off` removes nonessential motion and applies state changes immediately. Respect Android's system animation/accessibility preferences where available. Keep audio crossfade settings separate because they change sound, not visual motion.
- Audit all motion surfaces before claiming a setting is complete: playback art, simulated spectrum bars, progress interpolation, drag/snap-back, list auto-scroll, navigation transitions, and other screen animations. Components should consume one policy rather than each inventing different interpretations.
- A motion setting is useful because Terminus has optional playback-art and spectrum animations, and the preference system already provides a shared source of truth. Do not add it as an isolated toggle that only affects one component or causes extra per-frame preference work.

## Audit-confirmed safe areas

Do not spend time re-fixing, simplifying, or weakening safeguards recorded in [`files/06_verified_safe_do_not_touch.md`](files/06_verified_safe_do_not_touch.md) unless a new change makes one relevant. This includes, among others:

- the bounded nested lyrics list in Now Playing;
- safe album/artist/playlist keys and Home's distinct mix;
- chart point-count, zero-total, and divisor guards already documented there;
- null-safe metadata and lyrics parsing, and the absence of `!!`/unchecked casts;
- encoded album navigation routes;
- the permission gate around MediaStore access and the distinction between that gate and missing-column handling;
- import-stream null handling and display-name cursor checks.

The audit did identify specific real issues separately. Use the task handoff documents under `files/` to understand their intended scope, then verify whether the current code has already fixed them before making another change. A safe-path note is a constraint against unnecessary edits, not a reason to ignore a regression introduced by new code.

## Practical completion checklist

Before handing work back, confirm:

- [ ] The change is focused and its related callers/features were checked.
- [ ] No blocking, expensive, or repeated work was added to Main/composition/scroll/frame paths.
- [ ] Coroutine cancellation and lifecycle ownership are correct.
- [ ] Persisted data and provider identities remain consistent; migrations preserve user data.
- [ ] Existing safe behavior was left intact unless the change made it newly relevant.
- [ ] Relevant compile/tests pass, or their blockers and unverified device behavior are stated.
- [ ] Performance claims have before/after evidence, or are explicitly marked unmeasured.
