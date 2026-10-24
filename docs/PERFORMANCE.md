# Performance Measurement & Profiling Guide

This document defines the benchmarking and profiling infrastructure for **Terminus Player**: how to measure cold startup time, frame pacing, jank percentages, and Jetpack Compose compiler stability.

> [!IMPORTANT]
> **Measurement status:** the first DEVICE baseline was recorded on 2026-10-22 (section 4). It is the reference that later work compares against. Cells without a real run read **NOT MEASURED**. Numbers are labelled **DEVICE** (with model, Android version and build type) or **JVM/HOST** (unit tests / compiler reports on the build machine). JVM/HOST numbers say nothing about on-device performance.

---

## 1. Tooling Infrastructure Overview

The performance toolkit consists of 4 complementary layers:

1. **Macrobenchmark & Baseline Profile Module (`:benchmark`)**:
   - Built as an isolated `com.android.test` module targeting `:app`.
   - Uses `androidx.benchmark:benchmark-macro-junit4` to measure cold startup latency (`StartupTimingMetric`) and frame pacing (`FrameTimingMetric`) with `CompilationMode.None` vs `CompilationMode.Partial` (Baseline Profile).
   - Generates human-verified baseline profile rules via [`BaselineProfileGenerator`](file:///home/amns/Projects/Terminus/benchmark/src/main/java/com/necroware/terminusplayer/benchmark/BaselineProfileGenerator.kt).

2. **AOT Baseline Profile Installer (`:app`)**:
   - `androidx.profileinstaller:profileinstaller` compiles baseline profile rules into native code on app install or update, eliminating JIT compilation stutters on cold start and critical journeys.

3. **Runtime Frame Telemetry via JankStats (`:app`)**:
   - `androidx.metrics:metrics-performance` monitors UI frame durations and frame deadline overruns directly on device.
   - Initialized in [`JankStatsHelper.kt`](file:///home/amns/Projects/Terminus/app/src/main/java/com/necroware/terminusplayer/util/JankStatsHelper.kt) and active in `benchmark` and `debug` build variants (`BuildConfig.ENABLE_JANK_STATS`).
   - Automatically tags frame metrics with the active Compose screen route (`Screen=Library`, `Screen=NowPlaying`, etc.).

4. **Compose Compiler Stability Reports (Kotlin 2.0+)**:
   - Generates detailed compiler reports identifying restartable non-skippable composables and unstable data structures.

---

## 2. Benchmark Variant Setup

There are two release-like variants, and they differ in one way that matters:

| Variant | Created by | Used for | `ENABLE_JANK_STATS` |
| :--- | :--- | :--- | :---: |
| `benchmarkRelease` | `androidx.baselineprofile` plugin, copied from `release` | Every `:benchmark` Macrobenchmark (`installBenchmarkRelease`) | `false` |
| `benchmark` | `:app` build type (`initWith(release)`, debug-signed, `isDebuggable = false`) | JankStats logcat sessions (`assembleBenchmark`) | `true` |

Both are R8-minified with resource shrinking and debug-signed, so either can be installed over the other with `adb install -r` without losing app data. Macrobenchmark runs therefore have JankStats compiled out; JankStats numbers come from a separate session on the `benchmark` build type.

---

## 3. Step-by-Step Device Execution Commands

### Prerequisites
- Connect a physical Android device running Android 7.0 (API 24)+ via USB with developer options and USB debugging enabled.
- For optimal, repeatable Macrobenchmark timing:
  - Close background apps.
  - Keep battery above 25% and device connected to USB.
  - Disable stay-awake screen animations in developer settings or let the benchmark runner manage them.

---

### Step 1: Generate Baseline Profile

To generate the baseline profile using the connected device:

```bash
./gradlew :benchmark:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
```

> [!NOTE]
> If running on a non-rooted device or emulator and encountering environment warnings, add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY`.

The resulting baseline profile rules are automatically deposited into `app/src/main/baselineProfiles/baseline-prof.txt`.

---

### Step 2: Run Macrobenchmarks

> [!WARNING]
> `connected…AndroidTest` tasks **uninstall the app when they finish**, which deletes its library, playlists and settings on a personal device. The 2026-10-22 baseline was run without them: install with Gradle, then start the instrumentation on the device, detached from adb so a USB reset cannot kill the run:
>
> ```bash
> ./gradlew :app:installBenchmarkRelease :benchmark:installBenchmarkRelease
> P=com.necroware.terminusplayer.benchmark
> adb shell "nohup am instrument -w -r \
>   -e class $P.StartupBenchmark,$P.TabSwitchFrameTimingBenchmark,$P.LibraryScrollBenchmark,$P.PlayerPanelBenchmark \
>   $P/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/bench.txt 2>&1 &"
> # when /data/local/tmp/bench.txt contains INSTRUMENTATION_CODE:
> adb pull /data/local/tmp/bench.txt
> ```
>
> On Git Bash for Windows, `export MSYS_NO_PATHCONV=1` first so `/data/...` paths are not rewritten. Keep the screen awake for the whole run (`adb shell svc power stayon usb`, and `stayon false` afterwards).

The Gradle commands below still work on a device whose app data you do not need:

#### A. Startup Benchmark
Measures cold start latency (`timeToInitialDisplayMs`):

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.necroware.terminusplayer.benchmark.StartupBenchmark
```

#### B. Bottom Nav Tab Switch Benchmark
Measures frame timings while cycling through HOME, LIBRARY, PLAYLISTS, STATS, and SETTINGS:

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.necroware.terminusplayer.benchmark.TabSwitchFrameTimingBenchmark
```

#### C. Library Song List Fling Benchmark
Measures frame durations, p50/p90/p99 frame times, and frame deadline misses while flinging the main list:

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.necroware.terminusplayer.benchmark.LibraryScrollBenchmark
```

#### D. Player Panel Benchmark
`PlayerPanelBenchmark` (`FrameTimingMetric`), three journeys, 5 iterations each. Setup selects ALBUM ART (journeys a, b) or WARP VISUALIZER (journey c) with FULL motion through the Settings UI, then plays the first track from Library → `[ LOCAL MUSIC ]` so nothing depends on the network:
- **(a) open/close**: tap the mini bar, wait until fully open, swipe down on the art, wait until collapsed; ×10.
- **(b) skip**: in the expanded player, `>>|` ×10 then `|<<` ×10, 800 ms apart.
- **(c) visualizer**: expanded and playing for 20 s.

Needs at least one on-device track. Setup leaves the art style changed; reset it in Settings afterwards.

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.necroware.terminusplayer.benchmark.PlayerPanelBenchmark
```

#### E. Detail Navigation Benchmark
`DetailNavBenchmark` (`FrameTimingMetric`), 5 iterations: Library → `[ ALBUMS ]` → first album → back, ×10. Setup selects FULL motion and leaves a paused on-device track loaded, so the mini bar is present. On the S21 FE its Perfetto recordings often stop early (see the Phase 4 comparison in section 4); check that each trace's `measureBlock` slice is closed before using its numbers.

#### F. Run All Benchmarks at Once
```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

Benchmark output reports (JSON + markdown metrics) are saved to:
`benchmark/build/outputs/connected_android_test_additional_output/`

---

### Step 3: Stream Real-Time JankStats Telemetry

To view live frame durations and jank summaries while navigating the app:

```bash
adb logcat -s TerminusJank:I
```

`TerminusJank:I` already includes warnings. Do not add a second `TerminusJank:W` filter: the later spec overrides the first and hides every `Summary` line.

The `Summary` line is **cumulative since the activity was created**, not per screen; `States` only names the screen of the frame that triggered it. To get one screen's numbers, take the difference between two `Summary` lines logged while that screen was showing.

**Logcat line format** (placeholders in `<angle brackets>`, not measurements):
```text
W/TerminusJank: Jank frame: <duration>ms | States: [Screen=<route>]
I/TerminusJank: Summary | Frames: <count> | Jank: <count> (<percent>%) | Avg Duration: <duration>ms | States: [Screen=<route>]
```

---

### Step 4: Generate Compose Compiler Metrics & Stability Reports

To analyze class stability and composable skippability:

```bash
./gradlew assembleDebug -PenableComposeCompilerReports=true --rerun-tasks
```

Outputs will be generated in:
- `app/build/compose_reports/app_debug-classes.txt` — Class stability analysis (`stable`, `unstable`, `runtime`).
- `app/build/compose_reports/app_debug-composables.txt` — Composable functions (`restartable skippable` vs `restartable non-skippable`).
- `app/build/compose_reports/app_debug-composables.csv` — Composable metrics spreadsheet.
- `app/build/compose_metrics/app_debug-module.json` — Module-level Compose statistics.

#### Finding Key Optimization Targets:
1. **Find Unstable Classes:**
   ```bash
   grep -E "^unstable class" app/build/compose_reports/app_debug-classes.txt
   ```
2. **Find Non-Skippable Composables:**
   ```bash
   grep -B 1 "restartable" app/build/compose_reports/app_debug-composables.txt | grep -v "skippable"
   ```

---

## 4. Baseline Measurement Records

### Baseline run: 2026-10-22 (DEVICE)

| | |
| :--- | :--- |
| Device | Samsung Galaxy S21 FE, `SM-G990E`, 120 Hz display |
| OS | Android 16 (API 36), build `G990EXXSOHZH4` (`user/release-keys`) |
| App build | `benchmarkRelease` (R8, debug-signed, not debuggable) from commit `36984ce` on `poweramp-polish` |
| Macrobenchmark | `androidx.benchmark:benchmark-macro-junit4` 1.4.1, 5 iterations per test, run with `am instrument` (section 3) |
| Conditions | Wi-Fi off, mobile data on; battery 85% and charging over USB; 33.1 °C; thermal status 0; power saver off; screen kept on with `svc power stayon usb` |
| Result | `OK (12 tests)` in 3,586.8 s |

**`Partial` compiles with library-shipped profiles only.** The repo has no app baseline profile (`app/src/release/generated/baselineProfiles` is empty), so `CompilationMode.Partial` here does not measure an app-specific profile.

`StartupBenchmark`, `TabSwitchFrameTimingBenchmark` and `LibraryScrollBenchmark` touch the network only for whatever the screens load on their own (with Wi-Fi off, server artwork and lists come from cache or fail). `PlayerPanelBenchmark` plays on-device files only.

### Table 1: Cold Startup Latency (`StartupBenchmark`), DEVICE

| Mode | Iterations | Median `timeToInitialDisplay` (ms) | Min (ms) | Std dev (ms) | Max (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `CompilationMode.None` | 5 | 877.4 | 868.8 | 23.3 | NOT RECORDED |
| `CompilationMode.Partial` | 5 | 881.3 | 859.6 | 24.7 | NOT RECORDED |

- The two modes differ by 3.9 ms at the median, less than one standard deviation: no measurable difference.
- `MainActivity` keeps the splash screen up for at least `MIN_SPLASH_DURATION_MS = 650` ms, so this metric cannot show startup gains below that floor.
- The instrumentation output reports median, min and standard deviation only, so max is NOT RECORDED.

---

### Table 2: Frame Timing (`FrameTimingMetric`), DEVICE

`frameDurationCpuMs` is CPU time per frame. `frameOverrunMs` is how far a frame missed its deadline; negative means it finished early. Percentiles are over all frames of all 5 iterations. "Frames" is the median frame count per iteration (min / std dev).

| Journey | Mode | Frames (min / sd) | CPU p50 | CPU p90 | CPU p95 | CPU p99 | Overrun p50 | Overrun p90 | Overrun p95 | Overrun p99 |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Tab switch (5 tabs) | `None` | 156 (154 / 1.1) | 4.2 | 16.9 | 21.0 | 29.0 | -0.7 | 12.9 | 19.9 | 38.3 |
| Tab switch (5 tabs) | `Partial` | 156 (150 / 4.3) | 4.3 | 15.6 | 17.7 | 25.3 | -0.2 | 11.4 | 14.1 | 28.8 |
| Library SONGS fling down + up | `None` | 63 (59 / 1.9) | 10.8 | 18.3 | 20.8 | 27.3 | 5.7 | 13.6 | 16.1 | 22.0 |
| Library SONGS fling down + up | `Partial` | 63 (61 / 1.3) | 12.0 | 17.8 | 19.7 | 24.0 | 6.3 | 12.9 | 14.9 | 19.5 |
| Player (a) open/close ×10 | `None` | 2044 (2012 / 15.8) | 6.2 | 11.5 | 13.7 | 21.7 | 1.3 | 6.7 | 9.2 | 23.5 |
| Player (a) open/close ×10 | `Partial` | 2113 (2082 / 52.6) | 5.2 | 8.9 | 11.7 | 16.6 | -0.0 | 3.9 | 7.0 | 14.8 |
| Player (b) skip next ×10, prev ×10 | `None` | 2023 (1947 / 50.5) | 6.0 | 10.0 | 11.6 | 17.5 | -0.1 | 5.5 | 9.0 | 28.6 |
| Player (b) skip next ×10, prev ×10 | `Partial` | 1951 (1937 / 32.1) | 6.7 | 11.2 | 13.2 | 20.3 | 0.8 | 7.7 | 15.3 | 38.1 |
| Player (c) Visualizer, FULL, 20 s | `None` | 1732 (1668 / 39.7) | 7.3 | 11.3 | 12.6 | 16.6 | 0.6 | 4.8 | 6.4 | 11.6 |
| Player (c) Visualizer, FULL, 20 s | `Partial` | 1685 (1338 / 164.1) | 7.3 | 11.3 | 12.8 | 17.6 | 0.6 | 4.8 | 6.7 | 14.1 |

All values in ms. Notes for reading them:
- Journeys (a) and (b) include idle time (1 s settle pauses, 800 ms between skips), and frames keep coming while idle because the terminal title cursor blinks and the playing track's progress updates. Per-frame percentiles are still valid; total frame count is not a measure of animation length.
- Visualizer `Partial` has one low iteration (1,338 frames against a 1,685 median). The cause was not investigated.
- `None` vs `Partial` differences in this table are not evidence of a profile benefit (see the note above Table 1).

---

### Table 3: JankStats NowPlaying session, DEVICE

Build: `:app` `benchmark` build type (JankStats on), installed with `adb install -r` over the run above; ART compilation state not controlled. Same device, OS and conditions as the baseline run. Session: WARP VISUALIZER art, FULL motion, first Library → `[ LOCAL MUSIC ]` track playing, full player open for 60 s (15:12:43.8 → 15:13:44.0).

The window is the difference between two cumulative `Summary` lines, both tagged `Screen=NowPlaying`:

```text
15:12:43.813 I/TerminusJank( 6865): Summary | Frames: 1200 | Jank: 8 (0.7%) | Avg Duration: 3.2ms | States: [Screen=NowPlaying]
15:13:44.028 I/TerminusJank( 6865): Summary | Frames: 7440 | Jank: 10 (0.2%) | Avg Duration: 2.5ms | States: [Screen=NowPlaying]
```

| Screen | Window | Frames | Janky frames | Jank % | Avg frame duration (ms) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `NowPlaying` | Visualizer playing, 60.2 s | 6,240 | 2 (42 ms, 18 ms) | 0.03% | 2.30–2.44 |
| `Library` | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| `Home` | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| `Playlists` | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| `Settings` | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

The average is a range because the log prints cumulative averages rounded to 0.1 ms; the range is the full window average implied by those rounding bounds. The two janky frames are the `Jank frame: 42ms` (15:13:02.800) and `Jank frame: 18ms` (15:13:18.092) warnings inside the window.

---

### Phase 3 comparison: journey (b), skip next ×10 then previous ×10 (DEVICE)

Same device and OS as the baseline. All runs on 2026-10-22, `benchmarkRelease`, installed with `adb install-multiple -r <apk> baselineProfiles/0/<dm>` (the profile Gradle installs on API 31+), `PlayerPanelBenchmark` from commit `36984ce`, Macrobenchmark 1.4.1, 5 iterations per run, Wi-Fi off. Values in ms.

**Run 1: Phase 3 build against the morning baseline** (20:35–20:48, start 33.8 °C thermal 0 / battery 49%, end 37.1 °C **thermal 2**):

| Mode | Build | Frames median (min / sd) | CPU p50 | CPU p90 | CPU p95 | CPU p99 | Overrun p50 | Overrun p90 | Overrun p95 | Overrun p99 |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `None` | Baseline (`36984ce`, midday) | 2023 (1947 / 50.5) | 6.02 | 10.04 | 11.60 | 17.53 | -0.07 | 5.51 | 9.01 | 28.63 |
| `None` | Phase 3 (`08773ef`) | 1878 (1846 / 28.1) | 7.01 | 11.87 | 14.76 | 25.77 | 1.25 | 10.33 | 22.34 | 48.11 |
| `Partial` | Baseline (`36984ce`, midday) | 1951 (1937 / 32.1) | 6.67 | 11.19 | 13.16 | 20.32 | 0.79 | 7.71 | 15.32 | 38.12 |
| `Partial` | Phase 3 (`08773ef`) | 1906 (1849 / 35.9) | 7.03 | 11.89 | 14.44 | 23.99 | 1.44 | 9.93 | 20.06 | 43.99 |

The phone heated into thermal status 2 during this run and differed from the baseline in battery level and time of day, so this table alone does not show what caused the difference.

**Run 2: controlled A/B in one session** (`None` only; the runner waited for thermal status 0 before each variant):

| Order | Variant | APK sha1 | Start → end | Frames median (min / sd) | CPU p50 | CPU p90 | CPU p95 | CPU p99 | Overrun p50 | Overrun p90 | Overrun p95 | Overrun p99 |
| :---: | :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | A: before Phase 3 (`a759c58`) | `ca8f07b1` | 33.4 °C th0 → 35.3 °C th0 | 1960 (1935 / 70.1) | 6.31 | 10.52 | 12.23 | 19.71 | 0.34 | 6.42 | 13.37 | 35.55 |
| 2 | B: Phase 3 (`08773ef`) | `bee5bb7c` | 35.3 °C th0 → 35.9 °C th1 | 1851 (1817 / 31.9) | 7.14 | 12.23 | 14.86 | 26.51 | 1.77 | 10.20 | 22.55 | 48.30 |
| 3 | C: Phase 3, neighbour art prefetch disabled (uncommitted) | `33a4c472` | 35.0 °C th0 → 36.1 °C th1 | 1899 (1878 / 16.5) | 7.09 | 11.91 | 14.60 | 26.34 | 1.62 | 9.70 | 21.17 | 48.17 |
| 4 | A again (`a759c58`) | `ca8f07b1` | 35.0 °C th0 → 36.3 °C th1 | 1286 (1020 / 211.7) | 6.49 | 11.99 | 15.79 | 26.72 | 1.99 | 14.23 | 27.42 | 48.68 |

Reading it:
- **Neighbour artwork prefetch is not the cause:** C matches B within run-to-run noise.
- **The p95/p99 tail cannot be attributed to Phase 3:** the unchanged pre-Phase-3 build (run 4) reached the same tail once the phone had warmed up.
- **The median is the consistent signal:** both Phase 3 builds measured 7.09–7.14 ms CPU p50, both A runs 6.31–6.49 ms. That points to a possible cost of 0.6–0.8 ms per frame from Phase 3. **Open item, cause not identified.**
- **Run 4 is anomalous:** far fewer frames (one iteration produced 1,020) and iterations about 40–50 s apart instead of about 65–70 s. The cause was not investigated, so it is a weak drift check.
- The phone dropped off USB briefly during run 1 (detached instrumentation kept running; charging paused).

### Phase 4 comparison: tab switch and Library → Album → back (DEVICE)

Same device and OS as the baseline (Galaxy S21 FE `SM-G990E`, Android 16, build `G990EXXSOHZH4`). All runs on 2026-10-24, `benchmarkRelease` installed with `adb install-multiple -r <apk> baselineProfiles/0/<dm>`, Wi-Fi off, battery 100% on USB, every run started and ended at thermal status 0. Values in ms.

| Variant | Commit | App APK sha1 |
| :--- | :--- | :--- |
| A: before Phase 4 (adds only the album test tags) | `1300652` | `28abb141` |
| B: Phase 4 (play/pause morph + detail-page chrome) | `6bfcf00` | `74111ebc` |

**Tab switch** (`TabSwitchFrameTimingBenchmark`, 5 iterations, order A → B → A, 13:02–13:57):

| Mode | Run | Frames median (min / sd) | CPU p50 | CPU p90 | CPU p95 | CPU p99 | Overrun p50 | Overrun p90 | Overrun p95 | Overrun p99 |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `None` | 1 A | 155 (139 / 14.0) | 3.69 | 16.22 | 19.57 | 27.49 | -1.89 | 12.99 | 17.70 | 38.68 |
| `None` | 2 B | 156 (139 / 8.5) | 3.92 | 16.58 | 20.54 | 28.67 | -1.45 | 13.37 | 19.82 | 35.23 |
| `None` | 3 A | 154 (141 / 7.9) | 3.90 | 17.17 | 20.72 | 31.07 | -0.81 | 14.48 | 19.51 | 35.55 |
| `Partial` | 1 A | 141 (138 / 7.3) | 4.35 | 15.02 | 16.90 | 24.89 | -0.49 | 12.07 | 14.25 | 27.29 |
| `Partial` | 2 B | 152 (141 / 7.2) | 4.19 | 15.82 | 18.67 | 23.47 | -1.08 | 12.75 | 15.39 | 23.05 |
| `Partial` | 3 A | 148 (139 / 8.5) | 4.29 | 15.80 | 18.71 | 25.47 | -0.63 | 12.63 | 15.49 | 22.33 |

B falls inside the spread of the two A runs on every column: no measurable tab-switch change. (Tab routes are not affected by the detail-page change; the morph only runs on play/pause.)

**Library → Album → back ×10: Macrobenchmark results not usable.** `DetailNavBenchmark` ran twice (A → B → A each time). Its Perfetto recordings stopped early in many iterations: the `measureBlock` slice never closes, and the phone logged `perfetto: Timeout while waiting for ACKs for tracing session`. Counting from the traces of the second run: A recorded the whole journey in 18 of 20 iterations (about 1,130 frames, 20 taps each); B in 1 of 10. Macrobenchmark still reports percentiles for the cut-off iterations, so its numbers for this journey are not comparable and are not quoted here. Cause not found. The UiAutomator idle-wait cap (commit `2bfbc08`) removed a 16–30 s idle stretch per iteration but did not fix the truncation.

**Library → Album → back ×10: manual Perfetto traces.** Same journey driven by `adb shell input` (tap first album, `KEYCODE_BACK`, 1.5 s apart), one 36 s `perfetto` session per run (`sched freq gfx view input`, app atrace), alternating A/B, 14:54–14:58. Transitions are the bursts of `Choreographer#doFrame` on the main thread (a gap over 150 ms ends a burst). Frame CPU = `doFrame` + the following RenderThread `DrawFrame`, over all transition frames.

| Run | Open album: length median | Back: length median | Frames per transition (median) | Frame rate during transitions | Frame CPU p50 | p90 | p95 | p99 | Frames |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| A1 | 443 | 457 | 54 | 120 fps | 5.35 | 15.95 | 16.44 | 26.79 | 1,080 |
| B1 | 380 | 312 | 43 | 121 fps | 6.27 | 16.22 | 17.10 | 28.06 | 837 |
| A2 | 439 | 457 | 54 | 120 fps | 5.00 | 15.88 | 16.54 | 25.41 | 1,078 |
| B2 | 379 | 307 | 42 | 119 fps | 9.89 | 16.27 | 16.83 | 26.21 | 836 |

Reading it:
- **Both hold about 120 fps through the transitions.** B produces fewer frames because its transitions end sooner (about 60 ms on open, 150 ms on back), not because it misses frames. A keeps drawing after the screen transition, which fits the padding change and mini-bar re-anchoring that Phase 4 removed (not traced to the slice).
- **p90–p99 frame CPU match.** The **p50 is higher in B (6.27 and 9.89 against 5.00 and 5.35) and differs a lot between the two B runs.** B no longer has A's run of trailing frames, which may be cheap ones, so its median comes from a different mix. Not proven. **Open item.**
- `dumpsys gfxinfo` over the same journey (3 alternating pairs, 14:47–14:52) agrees on frame counts (A 995–1,006, B 702–705; janky 5.2–6.0% for both) but its percentiles include idle frames (cursor blink), so they are not quoted as a frame-time comparison.

---

### Table 4: Compose Stability Overview

Run `./gradlew assembleDebug -PenableComposeCompilerReports=true` to populate.

**JVM/HOST** (Compose compiler report, static analysis; not a runtime measurement). The "Before Phase 5" column was never saved as a compiler report, so it is NOT RECORDED. The "After Phase 5" values were copied from an earlier report and have not been regenerated since.

| Metric | Before Phase 5 | After Phase 5 (JVM/HOST) | Notes / Resolution |
| :--- | :---: | :---: | :--- |
| **Total Composables** | NOT RECORDED | 295 | Restartable: 294, Readonly: 0 |
| **Skippable Composables** | NOT RECORDED | 131 (Strong Skipping: 294) | Kotlin 2.0 Strong Skipping active across all composables |
| **Non-Skippable Composables** | NOT RECORDED | 0 | 100% of restartable composables are skippable |
| **Effectively Stable Classes** | NOT RECORDED | 17 | Marked: 16, Inferred: 1 |
| **Unstable Classes (Domain/UI)** | NOT RECORDED | 0 | 7 remaining are Hilt ViewModels (expected) |
| **Known Stable Arguments** | NOT RECORDED | 5,186 (99.0%) | Unstable args 1.0% |

---

### Table 5: EQ DSP Cost (`TerminusEqualizerAudioProcessorTest.benchmarkCpuCostPerSecondOfStereoAudio`)

**JVM/HOST only.** The unit test times 5 s of 44.1 kHz stereo audio through the 5-band EQ (gains +6/−3/+12/−6/+9 dB) on the build machine's JVM, using wall-clock `System.nanoTime()`. It does not measure CPU time on an Android device. DEVICE DSP cost is NOT MEASURED.

| Encoding | Run | Host | ms per 1 s of audio | % of one core |
| :--- | :--- | :--- | :---: | :---: |
| `PCM_FLOAT` | 2026-10-22, JDK 21.0.4 | Intel Core i5-7300U @ 2.60 GHz (Windows 10) | 22.923 | 2.292% |
| `PCM_16BIT` | 2026-10-22, JDK 21.0.4 | Intel Core i5-7300U @ 2.60 GHz (Windows 10) | 23.975 | 2.398% |

Earlier quoted figures of **1.42%** and **2.98%** were JVM/HOST measurements, not device numbers. The run, host and encoding behind them were not recorded.

---

## 5. Phase 5 UI Smoothness & State Retention Optimizations

### 1. Tab State & Flow Retention
- **Top-Level Navigation Scoping**: In `TerminusNavGraph.kt`, all 5 primary bottom-navigation ViewModels (`HomeViewModel`, `LibraryViewModel`, `PlaylistsViewModel`, `StatsViewModel`, `SettingsViewModel`) are scoped at the top-level navigation graph. Switching tabs preserves ViewModel instances, avoiding re-instantiation, re-subscription overhead, and Room query restarts.
- **State Flow Sharing**: UI state flows are backed by `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ...)` so upstream database emissions do not tear down during transient transitions.
- **Navigation State Restoration**: All bottom-nav tabs enforce `launchSingleTop = true`, `restoreState = true`, and `popUpTo { saveState = true }`.
- **Scroll State Persistence**: In `LibraryViewModel`, `songsListState`, `albumsListState`, and `artistsListState` are held inside the ViewModel and retained across tab switches, so returning to the Library screen restores the scroll position. Frame cost of the restore is NOT MEASURED; the behaviour is covered by manual check UI-2 in `DEVICE_TEST_CHECKLIST.md`.

### 2. LazyList Recycling & Allocation Hoisting
- **Unique Stable Keys & Content Types**: Added unique `key` lambdas and explicit `contentType` tags across every `LazyColumn`, `LazyRow`, `items`, and `itemsIndexed` block across all screens:
  - `HomeScreen`: Genre mixes (`contentType = "genre_mix"`), Recently played (`contentType = "recently_played_song"`), Smart mix (`contentType = "your_mix_song"`).
  - `LibraryScreen`: Tabs, albums, artists, YouTube tracks, playlist pickers, song rows (`contentType = "song_row"`, `"album_grid_item"`, etc.).
  - `AlbumDetailScreen`: Album track rows (`contentType = "album_track_row"`).
  - `PlaylistDetailScreen`: Playlist tracks (`contentType = "playlist_track_row"`), picker items (`contentType = "picker_song_row"`).
  - `PlaylistsScreen`: Quick mix pairs (`contentType = "quick_mix_row"`), playlist cards (`contentType = "playlist_item"`).
  - `StatsScreen`: Insights (`contentType = "insight"`), upload/download queues (`contentType = "upload_queue_item"`, `"download_queue_item"`), category rows (`contentType = "category_item"`).
  - `ManageSourcesScreen`: Source directories (`contentType = "folder_source"`).
- **Allocation Hoisting**: Removed in-scope allocations (e.g. `mix.take(10)`, queue reversal, pair chunking) from list scopes. All are hoisted using `remember(...)` keyed on input collections.
- **Prepared Song List Caching**: In `LibraryViewModel`, `cachedPreparedSongList` uses reference and structural equality checks to bypass redundant sectioning and header calculations when Room emits identical data sets.

### 3. Compose Compiler Stability Configuration
- **Custom Stability Configuration**: Configured `compose_compiler_config.conf` with `com.necroware.terminusplayer.data.model.*`, `com.necroware.terminusplayer.data.prefs.*`, and UI state wrappers.
- **`@Immutable` Data Structures**: Annotated UI state data classes across all screens:
  - `UserPreferences`, `EqualizerSettings`, `CrossfadeSettings`
  - `MainActivityThemeState`
  - `HomeUiState`
  - `PreparedSongList`, `SongListSection`
  - `PlaylistDetailUiState`, `PlaylistDownloadDialogState`, `UploadMatchResolutionState`
  - `StatsUiState`
  - `AlbumDetailUiState`
  - `MotionTokens`, `PanelMotionSpec`, `MotionTransitionSpec`
- **Results (JVM/HOST, compiler report)**: `effectivelyStableClasses` is 17 and 99% of composable arguments are statically known to be stable. No saved pre-change report exists to compare against. This is static analysis, not a measured runtime improvement.

### 4. Tab Navigation Transitions
- **Refined Tab Motion Spec**: Tab switches previously used a 100% full-screen slide (`slideFraction = 1f`). The change was made from code inspection; its frame-time effect is NOT MEASURED (no before/after device run exists).
- **Short 10% Slide + Fade**: Added `tabTransitionSpec` in `MotionTransitionPolicy.kt` with a subtle 10% horizontal slide and a 220 ms fade for bottom-nav navigation.
- **Preserved Detail Screen Motion**: Full-width hierarchy pushes and pops (`detailEnter` / `detailExit`) are preserved for drill-downs into albums, playlists, and settings sub-screens.

### 5. Verification & Test Suite Results
- **Unit Tests**: All 31 test suites in `:app:testDebugUnitTest` pass without regressions.
- **Minified Benchmark Target**: `:app:assembleBenchmark` compiles, minifies with R8, processes ART profiles, and packages successfully (`BUILD SUCCESSFUL in 3m 15s`).
