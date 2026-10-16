# Additional Settings Implementation Plan

Predictive pre-caching is excluded from this plan.

## Performance and animation policy

The Performance setting is separate from the existing Animations setting. The animation choice remains the user's motion limit; Performance can only keep that motion level or reduce it.

| Performance mode | Behavior |
| --- | --- |
| Auto | Follow Android Battery Saver. Otherwise use the Animations setting. |
| Full | Use the Animations setting without an additional cap. |
| Balanced | Cap Full motion at Reduced. |
| Battery Saver | Disable optional motion while preserving playback and interaction feedback. |

The current implementation adds this preference to DataStore, observes Battery Saver through a system event, and applies the resolved motion level to app navigation, playback, and settings section transitions. Battery state is not polled. This is the first part of the policy; the setting must also govern optional background work before it can be described as a general performance control.

## Work that can be deferred

Performance modes should govern optional work as well as visual motion, but they should not suspend arbitrary threads or interrupt every job indiscriminately. Use cooperative cancellation and durable scheduling so deferred work can resume later.

| Work | Policy |
| --- | --- |
| Playback, decoding, seeking, queue updates, user input | Always available. Never pause these for a performance mode. |
| User-started upload, download, import, or manual library sync | Honor explicit user intent. Keep the existing transfer pause/cancel controls; don't silently pause these on battery state alone. |
| Periodic library refresh and best-effort lyric cache warmup | Defer starting during Auto/Battery Saver. Leave them scheduled and run when the policy allows. Make retries idempotent. |
| Pending likes and playlist synchronization | Preserve local pending state. Defer network work when appropriate, then enqueue it when the policy allows; never drop pending updates. |
| Device status and audio telemetry polling | Collect only while the relevant UI is visible and the section is enabled. Reduce or stop optional measurements in Balanced/Battery Saver. |
| Decorative playback animation and future visual effects | Apply the effective motion and performance policies, and stop clocks when hidden, paused, or disabled. |

In this codebase, `LibrarySyncWorker` is periodic WorkManager work, `LyricsCacheWorker` is best-effort work, and likes/playlist updates have persisted pending state. WorkManager jobs should be deferred with durable constraints or explicit policy checks and resumed by scheduling work when the policy changes. Don't create a coroutine that waits in the background for battery state. Screen-scoped `produceState` loops should naturally cancel with their composable and must not be promoted to app-wide work.

## Feature order

1. **CRT scanline controls** — First locate and measure the existing scanline/CRT rendering path. Persist scanline intensity and phosphor-afterglow state. If scanlines do not exist, add them only to the intended playback surface. Afterglow is opt-in and must stop when the surface is hidden or the effect is disabled.
2. **Terminal cursor controls** — Add cursor glyph and blink cadence only to UI elements that already animate a cursor. A solid cursor must not create an animation clock. Respect both Animations and Performance policies.
3. **Adaptive album accent** — Extract a palette only when artwork changes, cache it by artwork identity, and preserve text contrast. Don't perform bitmap palette work during composition or animation frames.
4. **Sleep timer** — Own timer state with the playback lifecycle, cancel it when the user cancels or playback ends, and expose a countdown in Now Playing. Apply fade-out through the player volume over the selected final interval. The optional CRT collapse is visual only; the app must not claim it can turn off the device display.
5. **Haptic profile** — Use Android haptic feedback for existing interactions, with a user setting for clicky/tactile/off. Keep haptics independent from visual motion and honor system haptic settings.
6. **Smart streaming quality** — Keep the current static maximum bitrate as the manual choice. Add Auto only after confirming the provider's supported formats and bitrate options. Respond to connectivity changes through system callbacks, and avoid promising lossless if the server cannot provide it.
7. **Expanded HUD telemetry** — Show only values exposed by the player or server. Prefer event-driven updates; where polling is unavoidable, make its cadence explicit, stop it when the HUD is hidden, and suspend optional metrics in Battery Saver.
8. **Background work policy** — Add the mode gate to periodic library sync, best-effort lyric warmup, and pending remote sync. Preserve local pending state, do not pause user-started transfers, and enqueue deferred work when Auto/Battery Saver clears. Keep work durable through process death with WorkManager rather than a waiting coroutine.

## Performance rules for each feature

- Keep audio playback, seek, queue, and state feedback available in every mode.
- Gate continuous visual work on visibility, playback relevance, the feature toggle, the Animations preference, and the effective Performance mode.
- Do not disable static or one-time effects in power modes without evidence that they cost meaningful CPU, GPU, memory, or battery.
- Persist user choices through `UserPreferencesRepository`; derive runtime effective settings from preferences and system state rather than persisting temporary battery state.
- Add each feature in a focused change, inspect its callers and lifecycle, then compare the same playback scenario with the feature enabled and disabled before making performance claims.

## Completion order

- [x] Add the shared Performance mode preference and Auto Battery Saver observation.
- [x] Combine Performance mode with the existing Animations choice for navigation and playback motion.
- [x] Implement and profile the CRT controls (scanlines intensity and phosphor afterglow).
- [x] Add cursor settings (style & blink cadence) and adaptive album accents.
- [x] Add the sleep timer (fade-out & CRT collapse) and haptic profile (clicky, tactile, off).
- [x] Add smart streaming quality and measured HUD telemetry (expanded hacker mode).
- [x] Defer eligible background work and resume it safely when the effective mode allows.
- [x] Validate mode transitions, hidden-screen work cancellation, and accessibility behavior on device.
