# Terminus Handoff — P3: UI, Animation & Navigation Hardening

> Read `00_START_HERE_index_and_ground_rules.md` first. This file is mostly
> cosmetic/performance polish, not crashes — safe to tackle independently
> once P0/P1 land. Also see `06_verified_safe_do_not_touch.md` for chart
> code already confirmed defensive — don't redo that work here.

---

## 1. Search screen dead callback

**Location:** `ui/screens/search/SearchScreen.kt`

The screen declares `onSongClick`, but the result-click path directly
calls `viewModel.playSong(song, results)` instead of using the supplied
callback. The nav graph's callback (meant to play the song *and* open the
player) never fires. Because Search is in `hideChromeRoutes`, the
MiniPlayer is also hidden — so a tap can start playback with **no visible
feedback at all**.

**Fix:** call the supplied `onSongClick(song, results)` from the result
item. Remove the now-unused playback injection from `SearchViewModel` if
nothing else needs it.

---

## 2. Now Playing drag performance

**Location:** `NowPlayingScreen.kt:135,161`

Currently launches a new coroutine per drag event:
```kotlin
coroutineScope.launch {
    offsetY.snapTo(newOffset)
}
```
`Animatable` uses a `MutatorMutex`, so concurrent updates can cancel/reorder
each other. A high-refresh-rate device can generate ~120 pointer events/sec.

**Fix:** prefer `Modifier.draggable`, `anchoredDraggable`, or a single
long-lived coroutine fed by a `Channel`. Avoid spawning one coroutine per
pointer event.

---

## 3. AudioMonitorCard animation spec churn

**Location:** `AudioMonitorCard.kt:128-129`

```kotlin
durationMillis = (400..800).random()
delayMillis = (0..200).random()
```
These are evaluated during composition, so every recomposition can create
new animation specs. Since the animation drives `heightPercent`, this
creates a feedback loop: frame → animated state changes → recomposition →
new random spec → animation restarts → next frame. With 12 bars, each can
get a churning spec continuously.

**Fix:** hoist the randomized spec so it's stable across recompositions:
```kotlin
remember(barCount) { ... }
```
Preserve the intended visual randomness — just make the chosen values
stable rather than re-rolled every frame.

---

## 4. AudioMonitorCard / PlaybackArtStyles animate while paused

The infinite transition in AudioMonitorCard stays active even when
`isPlaying` is false — the output is merely visually masked, so it's still
doing ~60fps animation/recomposition work while appearing paused.

**Fix:** actually stop/freeze the animation when paused, rather than only
masking the displayed value.

---

## 5. PlaybackArtStyles `tween(0)` issue

**Location:** `PlaybackArtStyles.kt:39`

```kotlin
tween(
    if (isPlaying) 3000 else 0,
    easing = LinearEasing
)
```
Verified via bytecode inspection: `TweenSpec` accepts zero duration. With
an infinite repeat, a zero-duration animation spins as fast as the frame
clock allows — not actually paused.

**Fix:** don't use `tween(0)` as a pause mechanism. Only create/run the
animation while playing; otherwise use a frozen value (e.g. `0f`).

---

## 6. Animation values read in composition instead of draw phase

**Location:** `PlaybackArtStyles.kt:34-43`

Animated values are read into composition and passed into child
composables (CassettePlayer, ReelToReel, VinylRecord, VhsTape), which
invalidates more of the Compose subtree than necessary — including text
nodes that don't need to redraw every frame.

**Fix:** where practical, read animation state inside a Canvas draw lambda
or draw modifier, so only the drawing layer is invalidated.

---

## 7. Home has an unbounded 60fps scroll loop

**Location:** `HomeScreen.kt:101-113`

```kotlin
while (isActive) {
    listState.scrollBy(1f)
    delay(16)
}
```
Runs continuously while Home is composed — ~60 iterations/sec forever,
keeping CPU active, potentially fighting user interaction, and causing
unnecessary recomposition.

**Fix:** remove automatic scrolling, make it user-enabled, pause it while
the user is interacting, or replace it with a proper animation mechanism
(e.g. driven by an actual target rather than a manual per-frame loop).

---

## 8. `remember(key)` can discard user text edits

**Locations:** `SettingsScreen.kt:493-495`, `GapFinderScreen.kt:34`

State is keyed on incoming preference values. When SAVE changes
preferences, the key changes and Compose can recreate the remembered
state — so a user editing multiple fields, saving one, can have their
other unsaved edits silently reset.

**Fix:** seed state once with `remember { ... }` and synchronize external
preference changes via `LaunchedEffect`, or move editable state into the
ViewModel entirely.

---

## 9. Chart hardening (real risks only — see file 06 for what's already safe)

### LineChart `gridLines` divide-by-zero

`LineChart.kt:42` divides by `gridLines`. If `gridLines == 0`, this can
produce NaN/Infinity downstream. Current callers are safe because the
default is used, but the public component API itself isn't defensive.

**Fix:**
```kotlin
val safeGridLines = gridLines.coerceAtLeast(1)
```

### Negative chart values (Line/Area/Scatter)

These charts guard against a zero max but don't define a proper
negative-value coordinate system — negative values can render off-canvas.
**Fix:** either clamp data at the source if negative values are invalid
for this chart, or explicitly support signed ranges in chart scaling. Pick
one and document it.

### PieChart / BlockyBarChart negative values

Negative values can produce nonsensical negative sweep/geometry. **Fix:**
define and enforce an input contract — don't silently allow invalid
negative data if these components are only meant for non-negative values.

---

## 10. Navigation argument robustness

**Locations:** `PlaylistDetailViewModel.kt`, `AlbumDetailViewModel.kt`,
`ArtistDetailViewModel.kt`

Patterns like `checkNotNull(savedStateHandle["..."])` and
`PlaylistKind.valueOf(rawArg)` currently work because the app's own nav
wiring supplies valid arguments — these are **not normal-path bugs today**.
But they can fail under: restored stale back stacks, malformed/deep links,
future enum renames, or navigation graph changes.

**Fix:** parse defensively and expose an error/empty state instead of
throwing from a ViewModel constructor. For enums:
```kotlin
runCatching {
    PlaylistKind.valueOf(rawArg)
}.getOrNull()
```

---

## After this file: build & test checklist

- Confirm tapping a search result now navigates to the player and shows
  visible playback feedback.
- Confirm Now Playing drag feels smooth on a high-refresh-rate
  device/emulator setting.
- Confirm AudioMonitorCard bars no longer visibly "reset" their animation
  timing every recomposition, and actually stop animating (not just
  visually mask) when paused.
- Confirm Home's background scroll behaves as intended (removed, gated, or
  properly paused on interaction) and CPU/battery use drops when Home is
  idle on-screen — measure CPU/battery impact of Home and playback
  animations before/after.
- Confirm negative or zero-`gridLines` inputs to charts no longer produce
  NaN/garbled output.
- Confirm restoring a stale/malformed back stack or deep link into detail
  screens shows an error/empty state instead of crashing.

When done, move to `05_security_and_release_hardening.md`.
