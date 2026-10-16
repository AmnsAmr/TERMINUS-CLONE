# Playback Lifecycle & Audio Focus

This document details the audio playback pipeline, media session integration, audio focus transitions, and listening statistics tracking in **Terminus**.

---

## 1. Playback Architecture & Components

Terminus uses **AndroidX Media3** for audio playback. The architecture is split between UI-facing controllers and a standalone background service.

```mermaid
flowchart LR
    subgraph UIContext ["UI Process / Coroutine Scopes"]
        SCREEN["NowPlayingScreen"]
        VM["MainActivityViewModel"]
        PC["PlaybackController"]
        SCREEN -->|"User Tap / Seek / Skip"| VM
        VM -->|"Direct API Call"| PC
    end

    subgraph IPC ["MediaSession IPC"]
        TOKEN["SessionToken"]
        MC["MediaController"]
        PC <-->|"Async ListenableFuture"| MC
        MC <-->|"Binder IPC"| TOKEN
    end

    subgraph ServiceContext ["MusicService (Foreground Service)"]
        MS["MediaSessionService (MusicService)"]
        SESSION["MediaSession"]
        EXO["ExoPlayer"]
        EQ["EqualizerController"]
        TRACKER["ActivePlayTracker"]
        
        TOKEN <--> SESSION
        SESSION --> MS
        MS --> EXO
        EXO -->|"Audio Session ID"| EQ
        EXO -->|"Player.Listener"| TRACKER
    end

    subgraph AudioPipeline ["Android System Audio Engine"]
        AF["AudioManager (Audio Focus)"]
        NOTIF["MediaNotification (Lockscreen/System UI)"]
        MS <-->|"Request / Abandon Focus"| AF
        MS -->|"Foreground Notification"| NOTIF
    end
```

---

## 2. Playback Sequence: Starting a Track

When a user taps a song or a playlist item, the request flows through the following asynchronous steps:

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Screen as NowPlayingScreen
    participant VM as MainActivityViewModel
    participant PC as PlaybackController
    participant MS as MusicService
    participant Audio as AudioManager
    participant Exo as ExoPlayer
    participant Tracker as ActivePlayTracker
    participant DB as TerminusDatabase

    User->>Screen: Tap Play / Track Item
    Screen->>VM: onTrackSelected(song)
    VM->>PC: playTrack(song)
    
    PC->>MS: transportControls.playFromUri(uri, extras)
    Note over MS: Resolves stream URI & metadata
    
    MS->>Audio: requestAudioFocus(AUDIOFOCUS_GAIN)
    Audio-->>MS: AUDIOFOCUS_REQUEST_GRANTED
    
    MS->>Exo: setMediaItem(MediaItem)
    MS->>Exo: prepare()
    MS->>Exo: play()

    Exo-->>MS: onPlaybackStateChanged(STATE_BUFFERING)
    MS-->>PC: emit StateFlow (isBuffering = true)
    PC-->>VM: StateFlow updated
    VM-->>Screen: Recompose HUD (Buffering indicators)

    Exo-->>MS: onPlaybackStateChanged(STATE_READY)
    Exo-->>MS: onIsPlayingChanged(true)
    
    MS->>Tracker: onIsPlayingChanged(true, timestamp)
    MS-->>PC: emit StateFlow (isPlaying = true)
    PC-->>VM: StateFlow updated
    VM-->>Screen: Animate CRT scanline & audio spectrum bars

    Note over Exo,Tracker: Track plays until completion or skip
    Exo-->>MS: onMediaItemTransition(REASON_AUTO)
    MS->>Tracker: flushCurrentTrack(completed = true)
    Tracker->>DB: playEventDao.insert(PlayEventEntity)
```

---

## 3. Playback State Machine

The audio engine transitions across ExoPlayer lifecycle states, handling user commands and audio focus interrupts gracefully:

```mermaid
stateDiagram-v2
    [*] --> Idle: Service Created

    Idle --> Buffering: playTrack(song) / prepare()
    Buffering --> Playing: onPlaybackStateChanged(STATE_READY) & playWhenReady=true
    
    Playing --> Paused: User Pause / Headphone Disconnected (Becoming Noisy)
    Paused --> Playing: User Resume / Focus Regained
    
    Playing --> Ducked: AudioFocus Transient Loss (Ducking enabled)
    Ducked --> Playing: AudioFocus Gain Restored

    Playing --> Buffering: Seeking / Network Stream Underflow
    Buffering --> Playing: Buffer Restored

    Playing --> Ended: Track Reached Duration (STATE_ENDED)
    Ended --> Buffering: Next Track in Queue (Auto Transition)
    Ended --> Idle: Queue Empty

    Playing --> Error: Network / Decoder Failure
    Buffering --> Error: Stream Timeout / Unreachable Server
    Error --> Idle: Reset Player / Dismiss
    
    Paused --> Idle: Service Destroyed / App Closed
```

---

## 4. Audio Focus Policy

`MusicService` monitors system audio focus changes via `AudioManager`:

| Focus Event | Behavior |
| :--- | :--- |
| **`AUDIOFOCUS_GAIN`** | Resumes playback or restores volume to 100% if previously ducked. |
| **`AUDIOFOCUS_LOSS_TRANSIENT`** | Pauses playback temporarily (e.g., incoming phone call, turn-by-turn navigation). Resumes automatically when focus returns. |
| **`AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`** | Lowers playback volume (ducks) temporarily without pausing. |
| **`AUDIOFOCUS_LOSS`** | Permanent focus loss (another music app started). Pauses playback and abandons audio focus. |
| **`ACTION_AUDIO_BECOMING_NOISY`** | Headphones or Bluetooth unplugged/disconnected. Instantly pauses playback to prevent unwanted loud audio output. |

---

## 5. Active Play Tracking & Statistics

To populate the listening statistics screens (`StatsScreen`):
1. **Granular Listening Time**: `ActivePlayTracker` measures actual milliseconds spent playing, subtracting pause and buffering times.
2. **Qualification Threshold**: An event is marked `completed = true` when played past 50% of track duration (or 30 seconds for long audio).
3. **Database Insertion**: On track transition or playback stop, `MusicService` asynchronously writes a `PlayEventEntity` into `TerminusDatabase` via `StatsRepository` on `Dispatchers.IO`.
