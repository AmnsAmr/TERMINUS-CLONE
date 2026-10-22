# Performance Measurement & Profiling Guide

This document defines the benchmarking and profiling infrastructure for **Terminus Player**: how to measure cold startup time, frame pacing, jank percentages, and Jetpack Compose compiler stability.

> [!IMPORTANT]
> **Measurement status:** no device (DEVICE) Macrobenchmark or JankStats numbers have been recorded yet. Every device cell below reads **NOT MEASURED** until a real run fills it. Numbers are labelled **DEVICE** (with model, Android version and build type) or **JVM/HOST** (unit tests / compiler reports on the build machine). JVM/HOST numbers say nothing about on-device performance.

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

Terminus has a dedicated `benchmark` build type in `:app` configured specifically for Macrobenchmarks:
- `initWith(buildTypes.release)` (R8 minification enabled, resource shrinking enabled).
- Signed with the debug signing key (`signingConfig = signingConfigs.debug`).
- `isDebuggable = false` (accurate release-like performance).
- `ENABLE_JANK_STATS = true`.

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

Run the macrobenchmarks across all critical user journeys:

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

#### D. Run All Benchmarks at Once
```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

Benchmark output reports (JSON + markdown metrics) are saved to:
`benchmark/build/outputs/connected_android_test_additional_output/`

---

### Step 3: Stream Real-Time JankStats Telemetry

To view live frame durations and jank summaries while navigating the app:

```bash
adb logcat -s TerminusJank:I TerminusJank:W
```

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

Status: **NOT MEASURED** on a device. Fill these tables only from real physical-device runs, and record the device model, Android version, build type and date with each run.

### Table 1: Cold Startup Latency (`StartupBenchmark`)

| Mode | Iterations | Median Startup (`timeToInitialDisplayMs`) | Min (ms) | Max (ms) | Speedup vs None |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `CompilationMode.None` | 5 | NOT MEASURED | NOT MEASURED | NOT MEASURED | Baseline |
| `CompilationMode.Partial` (Profile) | 5 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

---

### Table 2: Frame Timing & Pacing (`FrameTimingMetric`)

| User Journey | Compilation Mode | Frame Time P50 (ms) | Frame Time P90 (ms) | Frame Time P99 (ms) | Missed Frame Deadlines (%) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Tab Switching** (5 tabs) | `None` | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| **Tab Switching** (5 tabs) | `Partial` (Profile) | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| **Library List Fling** | `None` | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| **Library List Fling** | `Partial` (Profile) | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

---

### Table 3: JankStats Summary per Screen

| Screen Name | Test Duration / Action | Total Frames Rendered | Janky Frames Count | Jank Percentage (%) | Avg Frame Duration (ms) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `Library` | Continuous Fling (10s) | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| `NowPlaying` | Player open + visualizer (10s) | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| `Home` | Fling smart mixes | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| `Playlists` | Scroll playlist cards | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| `Settings` | Expand dropdowns | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

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
