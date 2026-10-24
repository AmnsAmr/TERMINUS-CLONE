# Manual Device Test Checklist

This checklist provides a structured verification procedure for **Terminus Player** on physical Android hardware. It validates audio quality, DSP equalizer behavior, gapless playback, UI smoothness, state retention, and background reliability.

---

## 1. Test Setup & Equipment

### Prerequisites
- [ ] Physical Android device running Android 8.0 (API 26) through Android 14 (API 34)+.
- [ ] Audio outputs:
  - Wired headphones (3.5mm or USB-C DAC)
  - Bluetooth headphones/speaker
  - Built-in device speaker
- [ ] Test audio tracks:
  - High dynamic range / loud master track (0 dBFS normalized, e.g. modern electronic / rock)
  - Continuous gapless live or concept album (e.g. Pink Floyd's *The Dark Side of the Moon* or Abbey Road medley)
  - Standard 16-bit / 44.1 kHz FLAC / MP3 / AAC
  - Hi-res 24-bit / 96 kHz or 192 kHz FLAC (if available)
- [ ] ADB connection available for telemetry verification (`adb logcat -s TerminusJank:I TerminusJank:W`).

---

## 2. Audio & DSP Equalizer Verification

| # | Test Case | Action / Steps | Expected Behavior | Status |
| :-: | :--- | :--- | :--- | :---: |
| **EQ-1** | **EQ Toggle Mid-Track** | 1. Play audio.<br>2. Set non-zero gains (e.g., +6 dB Bass, +4 dB Treble).<br>3. Rapidly toggle the EQ ON and OFF multiple times while audio is actively playing. | - **Zero audible clicks, pops, or micro-stutters.**<br>- Gain smoothly transitions over ~25 ms without audio dropouts.<br>- Bit-identical bypass activates when OFF without pipeline flushes. | [ ] PASS<br>[ ] FAIL |
| **EQ-2** | **Rapid Slider Movement** | 1. Open Equalizer in Settings or Now Playing.<br>2. Vigorously drag the 60 Hz (Bass) and 14 kHz (Treble) sliders from -12 dB to +12 dB repeatedly. | - **No zipper noise or digital distortion.**<br>- Gains smoothly interpolate across 32-sample blocks.<br>- CPU usage remains negligible (< 3% of 1 core). | [ ] PASS<br>[ ] FAIL |
| **EQ-3** | **+12 dB Bass on Loud Master** | 1. Play a heavily mastered, loud 0 dBFS electronic/pop track.<br>2. Push 60 Hz slider to maximum (+12 dB).<br>3. Listen on high-fidelity headphones. | - **No harsh digital clipping or square-wave distortion.**<br>- Auto pre-gain (-12.5 dB = full compensation + 0.5 dB margin) keeps the boosted bass at about its original level while everything else gets quieter; no distortion from the tanh safety limiter.<br>- Overall loudness drops compared with EQ off; that is expected. | [ ] PASS<br>[ ] FAIL |
| **EQ-4** | **Mid-Track Seek** | 1. Enable EQ with active boosts.<br>2. Repeatedly scrub the playback progress bar forward and backward.<br>3. Test small jumps (+10s) and large track-spanning seeks. | - Playback resumes instantly at target position.<br>- Delay states are cleared upon flush (`onFlush`), preventing any residual echo or transient blast. | [ ] PASS<br>[ ] FAIL |
| **EQ-5** | **Gapless Album Transition** | 1. Queue a gapless continuous album.<br>2. Enable EQ.<br>3. Allow track $N$ to transition naturally into track $N+1$. | - **100% gapless transition with zero silence interval and zero click/pop.**<br>- EQ pipeline retains active coefficients without reconfiguring the sink between matching tracks. | [ ] PASS<br>[ ] FAIL |
| **EQ-6** | **Headphones & Speaker Routing** | 1. Play audio through headphones.<br>2. Unplug headphones or disconnect Bluetooth.<br>3. Verify audio pauses or switches to speaker according to system policy.<br>4. Reconnect headphones and resume. | - Audio focus and routing change cleanly handled.<br>- No processor crash or sample rate mismatch when switching between 48 kHz speaker and 44.1/96 kHz DAC. | [ ] PASS<br>[ ] FAIL |
| **EQ-7** | **16-Bit & Hi-Res Format Playback** | 1. Play 16-bit / 44.1 kHz track.<br>2. Verify Audio Monitor HUD.<br>3. Play 24-bit / 96 kHz or 192 kHz FLAC.<br>4. Inspect Audio Monitor HUD. | - Audio Monitor displays exact format details (e.g. `FLAC · 96.0 kHz · 24-bit · 2 CH`).<br>- Nyquist guard safely bypasses 14 kHz band when sample rate requires it.<br>- Output is pristine with zero distortion. | [ ] PASS<br>[ ] FAIL |
| **EQ-8** | **Spectrum Visualizer** | 1. Observe Now Playing spectrum visualizer with flat EQ.<br>2. Boost 60 Hz to +12 dB.<br>3. Cut 3.6 kHz to -12 dB. | - Low-frequency visualizer bars elevate proportionally.<br>- High-mid visualizer bars attenuate proportionally.<br>- Visualizer runs smoothly at full display refresh rate (60–120 Hz). | [ ] PASS<br>[ ] FAIL |

---

## 3. UI Smoothness, State Retention & Navigation

| # | Test Case | Action / Steps | Expected Behavior | Status |
| :-: | :--- | :--- | :--- | :---: |
| **UI-1** | **Bottom-Nav Tab Switching** | 1. Tap through all 5 bottom-nav destinations: `HOME` $\to$ `LIBRARY` $\to$ `PLAYLISTS` $\to$ `STATS` $\to$ `SETTINGS`.<br>2. Cycle back and forth rapidly. | - **Short, elegant 10% slide + fade (220 ms).**<br>- No full-screen layout thrashing or white flashes.<br>- Room flows and ViewModels remain alive; zero loading spinners on re-entry. | [ ] PASS<br>[ ] FAIL |
| **UI-2** | **Library Scroll Position Retention** | 1. Navigate to `LIBRARY` (Songs tab).<br>2. Scroll down several hundred tracks (e.g. to letter "M").<br>3. Switch to `STATS` or `SETTINGS`.<br>4. Return to `LIBRARY`. | - **Exact scroll position is preserved instantly.**<br>- Zero jump to top, zero layout re-computation, zero list flicker. | [ ] PASS<br>[ ] FAIL |
| **UI-3** | **Library List Fling Smoothness** | 1. Open `LIBRARY` with 1,000+ indexed tracks.<br>2. Perform aggressive top-to-bottom and bottom-to-top flings.<br>3. Monitor frame drops via JankStats. | - Fluid scrolling at target display refresh rate (60 Hz / 90 Hz / 120 Hz).<br>- Logcat `TerminusJank` reports < 2% jank frames.<br>- Content types and remembered keys ensure instant item recycling. | [ ] PASS<br>[ ] FAIL |
| **UI-4** | **Detail Screen Push/Pop Navigation** | 1. In `LIBRARY`, tap an Album or Playlist.<br>2. Verify entrance transition.<br>3. Press system Back or top back arrow. | - Smooth full-screen push and pop transitions (`detailEnter` / `detailExit`).<br>- Track list loads without stutter. | [ ] PASS<br>[ ] FAIL |

---

## 4. Lifecycle, Process Death & Edge Cases

| # | Test Case | Action / Steps | Expected Behavior | Status |
| :-: | :--- | :--- | :--- | :---: |
| **SYS-1** | **Process Death & Recreation** | 1. Start playback and adjust EQ settings.<br>2. Navigate deep into Library or Playlists.<br>3. Press Home to send app to background.<br>4. In terminal: `adb shell am kill com.necroware.terminusplayer`.<br>5. Re-launch Terminus from launcher. | - Playback service continues if foreground notification was active, or resumes gracefully.<br>- Equalizer gains and settings are restored accurately from DataStore.<br>- App boots up without crashing or ANR. | [ ] PASS<br>[ ] FAIL |
| **SYS-2** | **Low-End / Power Saver Device** | 1. Enable Android Battery Saver mode (throttles CPU and restricts background work).<br>2. Run audio playback with active EQ for 10 minutes while switching tabs. | - Audio playback remains uninterrupted without buffer underruns.<br>- Motion system automatically adapts (or switches to `MotionPreference.OFF` / reduced animation).<br>- No memory leaks or runaway battery drain. | [ ] PASS<br>[ ] FAIL |

---

## 5. Live Telemetry Verification Command

To observe real-time frame timings and jank statistics during the manual test run:

```bash
adb logcat -s TerminusJank:I TerminusJank:W
```

**Target Criteria:**
- Janky frame percentage on continuous flings: **< 2.0%**.
- Average frame render duration: **< 8.0 ms** (120 Hz budget: 8.33 ms, 60 Hz budget: 16.6 ms).
- No logcat warnings regarding dropped buffers or audio sink underruns.

---

## 6. Device Test Sign-Off

- **Device Model**: ________________________________________
- **Android Version / API**: ________________________________________
- **Tester Name**: ________________________________________
- **Date Tested**: ________________________________________
- **Overall Result**: `[ ] APPROVED` / `[ ] REJECTED`
- **Notes / Observations**:
  __________________________________________________________________________________________________
  __________________________________________________________________________________________________
