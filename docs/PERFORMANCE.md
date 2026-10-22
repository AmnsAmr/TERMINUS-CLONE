# Performance Measurement & Profiling Guide

This document defines the benchmarking and profiling infrastructure for **Terminus Player**, establishing data-driven baselines for cold startup time, frame pacing, jank percentages, and Jetpack Compose compiler stability.

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

**Example Logcat Output:**
```text
W/TerminusJank: Jank frame: 34ms | States: [Screen=Library]
I/TerminusJank: Summary | Frames: 120 | Jank: 2 (1.7%) | Avg Duration: 7.2ms | States: [Screen=Library]
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

## 4. Baseline Measurement Records (To Fill Before Phase 5)

Record the results from physical device test runs below:

### Table 1: Cold Startup Latency (`StartupBenchmark`)

| Mode | Iterations | Median Startup (`timeToInitialDisplayMs`) | Min (ms) | Max (ms) | Speedup vs None |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `CompilationMode.None` | 5 | _e.g. 420 ms_ | _e.g. 395 ms_ | _e.g. 450 ms_ | Baseline |
| `CompilationMode.Partial` (Profile) | 5 | _e.g. 260 ms_ | _e.g. 240 ms_ | _e.g. 280 ms_ | _e.g. +38%_ |

---

### Table 2: Frame Timing & Pacing (`FrameTimingMetric`)

| User Journey | Compilation Mode | Frame Time P50 (ms) | Frame Time P90 (ms) | Frame Time P99 (ms) | Missed Frame Deadlines (%) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **Tab Switching** (5 tabs) | `None` | | | | |
| **Tab Switching** (5 tabs) | `Partial` (Profile) | | | | |
| **Library List Fling** | `None` | | | | |
| **Library List Fling** | `Partial` (Profile) | | | | |

---

### Table 3: JankStats Summary per Screen

| Screen Name | Test Duration / Action | Total Frames Rendered | Janky Frames Count | Jank Percentage (%) | Avg Frame Duration (ms) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `Library` | Continuous Fling (10s) | | | | |
| `NowPlaying` | Player open + visualizer (10s) | | | | |
| `Home` | Fling smart mixes | | | | |
| `Playlists` | Scroll playlist cards | | | | |
| `Settings` | Expand dropdowns | | | | |

---

### Table 4: Compose Stability Overview

Run `./gradlew assembleDebug -PenableComposeCompilerReports=true` to populate:

| Metric | Before Phase 5 | After Phase 5 | Notes / Resolution |
| :--- | :---: | :---: | :--- |
| **Total Composables** | 295 | 295 | Restartable: 294, Readonly: 0 |
| **Skippable Composables** | 131 (Strong Skipping: 294) | 131 (Strong Skipping: 294) | Kotlin 2.0 Strong Skipping active across all composables |
| **Non-Skippable Composables** | 0 | 0 | 100% of restartable composables are skippable |
| **Effectively Stable Classes** | 1 | 17 | Marked: 16, Inferred: 1 |
| **Unstable Classes (Domain/UI)** | 16+ (All UI States & Models) | 0 | 7 remaining are Hilt ViewModels (expected) |
| **Known Stable Arguments** | ~3,100 | 5,186 (99.0%) | Unstable args reduced to 1.0% |

---

## 5. Phase 5 UI Smoothness & State Retention Optimizations

### 1. Tab State & Flow Retention
- **Top-Level Navigation Scoping**: In `TerminusNavGraph.kt`, all 5 primary bottom-navigation ViewModels (`HomeViewModel`, `LibraryViewModel`, `PlaylistsViewModel`, `StatsViewModel`, `SettingsViewModel`) are scoped at the top-level navigation graph. Switching tabs preserves ViewModel instances, avoiding re-instantiation, re-subscription overhead, and Room query restarts.
- **State Flow Sharing**: UI state flows are backed by `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ...)` so upstream database emissions do not tear down during transient transitions.
- **Navigation State Restoration**: All bottom-nav tabs enforce `launchSingleTop = true`, `restoreState = true`, and `popUpTo { saveState = true }`.
- **Scroll State Persistence**: In `LibraryViewModel`, `songsListState`, `albumsListState`, and `artistsListState` are held inside the ViewModel and retained across tab switches. Returning to the Library screen instantly restores exact scroll position with zero layout jumps or empty frame flashes.

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
- **Results**: `effectivelyStableClasses` increased from 1 to 17. 99% of composable arguments are statically known to be stable.

### 4. Tab Navigation Transitions
- **Refined Tab Motion Spec**: Tab switches previously performed an expensive 100% full-screen slide (`slideFraction = 1f`), causing heavy recomposition and layout thrashing across the entire display viewport.
- **Short 10% Slide + Fade**: Added `tabTransitionSpec` in `MotionTransitionPolicy.kt` with a subtle 10% horizontal slide and a 220 ms fade for bottom-nav navigation.
- **Preserved Detail Screen Motion**: Full-width hierarchy pushes and pops (`detailEnter` / `detailExit`) are preserved for drill-downs into albums, playlists, and settings sub-screens.

### 5. Verification & Test Suite Results
- **Unit Tests**: All 31 test suites in `:app:testDebugUnitTest` pass without regressions.
- **Minified Benchmark Target**: `:app:assembleBenchmark` compiles, minifies with R8, processes ART profiles, and packages successfully (`BUILD SUCCESSFUL in 3m 15s`).
