# UI/UX Polish & Feature Completion Guide

**To the next agent:** The user wants to elevate the UI/UX of this retro-terminal style music player. Here are the core areas that lack professional polish and need your attention, along with tasks that previously struggled to work correctly in the background.

## 1. Missing Professional UI/UX Elements to Add/Fix

The app uses a retro-hacker terminal aesthetic (brackets `[ ]`, monospace/system fonts, `TerminalBorder`). However, it lacks modern UX conveniences:

*   **Loading States:** Replace static "Loading..." text with terminal-style ASCII progress bars (e.g., `[####......] 40%`) or blinking cursor skeleton loaders.
*   **Empty States:** Add ASCII art or better visual hierarchy for empty lists (e.g., Stats, Upload Queue, Playlists) instead of just plain `[ nothing here yet ]` text.
*   **Interactive Feedback:** Add **haptic feedback (vibration)** to long-presses, action buttons, and queue interactions to make the app feel tactile.
*   **Swipe Gestures:** Implement swipe-to-dismiss in the Upload Queue (Stats page) and Playlists. Right now, managing lists is entirely dependent on clicking `[ CLEAR QUEUE ]` or relying on hidden long-press actions.
*   **Custom Snackbars/Toasts:** When an error occurs (e.g., upload rejected, network timeout), display a styled, terminal-themed Snackbar at the bottom of the screen instead of silently logging it or altering a small piece of text.
*   **Screen Transitions:** Add custom navigation transitions (e.g., instant cut, slight static/flicker, or fast slide) instead of the default Android Compose crossfades.

## 2. Incomplete / Failed Tasks to Fix

The previous attempts to implement **Audio Format Conversion** and **Upload Notifications** faced several blocking issues that need to be completely resolved and polished:

### Audio Format Conversion (Opus -> AAC)
*   **Issue:** The conversion relies on FFmpegKit, but the UI feedback for it is extremely poor. It often gets stuck in a `WAITING` or `0/3` state in the queue if the conversion takes too long or the network hangs.
*   **Action Required:**
    *   Create a dedicated `CONVERTING` state in the Database/UI so the user knows *why* a file is taking so long.
    *   Ensure the FFmpeg process can be safely cancelled if the user clears the queue.

### Upload Queue Notifications
*   **Issue:** Android 13+ Notification permissions (`POST_NOTIFICATIONS`) and Foreground Service (`FOREGROUND_SERVICE_DATA_SYNC`) constraints caused the progress notification to repeatedly fail to appear or update.
*   **Action Required:**
    *   Thoroughly test the Foreground Service lifecycle. If `startWorker()` is called while the app is in the background, Android 12+ prevents starting a foreground service. Use WorkManager's modern `setExpedited()` or ensure the background execution is rock-solid.
    *   Ensure the `ProgressRequestBody` accurately pipes byte-level progress to the notification without hanging `OkHttp`.
    *   Add a native "Cancel" button directly inside the Android Notification so the user doesn't have to open the app to stop a stuck upload.

## 3. High-Density List Navigation & Responsiveness (New)

The user has a massive library (3,000+ songs) and is experiencing friction with navigation and screen widths.

*   **Fast Scrubber / Alphabetical Index:** Add a draggable fast-scroll thumb (the "side thingy") to the right edge of the `LazyColumn`. If sorted alphabetically, it should display the current letter as the user scrubs. If sorted by date or album, it should display the relevant context.
*   **Responsive Action Menus:** The long-press action menu (and other popups) is currently too wide on smaller devices, causing text like `[action]` to wrap awkwardly to a second line.
    *   *Fix:* Make the `TerminalBorder` dialogs dynamically size to the screen width (e.g., `fillMaxWidth(0.9f)`) and use `maxLines = 1` with `AutoSizeText` or proper scaling so action labels always stay on one line.
*   **Sticky Headers:** When scrolling through massive lists sorted by Artist or Album, implement sticky headers so the user always knows what section they are looking at.

## 4. Advanced Polish & "Next-Level" Details

To make the app feel incredibly premium despite its retro aesthetic, implement these additional polish features:

*   **Marquee Text (Auto-Scrolling):** If a song title or artist name is too long to fit in the bottom playback bar or action menu, animate it with a smooth horizontal marquee scroll instead of cutting it off with an ellipsis `...`.
*   **Search Substring Highlighting:** When the user searches for a song, highlight the exact matching letters in the search results with a bright accent color (e.g., inverted terminal colors).
*   **Retro Audio Visualizer:** Add a blocky, ASCII-style audio visualizer (e.g., `▃▆█ ▂▃`) on the Now Playing screen that reacts to the music.
*   **Dynamic Accent Colors:** Extract colors from the currently playing Album Art using Android's Palette API and apply a subtle tint to the terminal theme elements (like the brackets or borders).
*   **Landscape & Tablet Layouts:** Prevent list items from stretching ridiculously wide in landscape mode. Implement a two-pane layout where the library is on the left and the Now Playing / Queue is on the right.
*   **Toggle Switch Polish:** In the settings screen, replace standard Android checkboxes/switches with customized retro toggles (e.g., `[ ON ]` vs `[OFF ]`).
