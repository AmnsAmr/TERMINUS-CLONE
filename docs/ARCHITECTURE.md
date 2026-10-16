# Terminus System Architecture

This document describes the high-level architecture, layer responsibilities, and data flows of the **Terminus** Android music application.

---

## 1. Architectural Overview

Terminus is built with modern Android architectural best practices:
- **Clean Architecture & Separation of Concerns**: Isolates UI, playback services, data providers, and persistence.
- **Unidirectional Data Flow (UDF)**: UI observes reactive state (`StateFlow`) and emits explicit user intents or actions.
- **Provider Abstraction**: Decouples the application from concrete storage backends via the `MediaProvider` interface, supporting both local device media (`LocalMediaProvider`) and remote Subsonic/Navidrome servers (`NavidromeProvider`).
- **Foreground Media Service**: Offloads audio decoding, audio focus, notification management, and media session synchronization to a dedicated AndroidX Media3 `MediaSessionService`.

```mermaid
flowchart TD
    subgraph UI ["Presentation Layer (Jetpack Compose)"]
        direction TB
        ACT["MainActivity / ExternalPlaybackActivity"]
        VM["MainActivityViewModel"]
        NAV["AppNavigation (Navigation Compose)"]
        
        ACT --> VM
        ACT --> NAV
        NAV --> SCREENS["Screens: Home, Player, Library, Search, Playlists, Stats, Upload, Settings"]
        VM --> SCREENS
    end

    subgraph StateBridge ["Playback Control & State"]
        PC["PlaybackController"]
        VM <-->|"Exposes StateFlow<NowPlayingState> & commands"| PC
    end

    subgraph ServiceLayer ["Playback Engine (AndroidX Media3)"]
        MS["MusicService (MediaSessionService)"]
        EXO["ExoPlayer (Audio Engine)"]
        SES["MediaSession & System Notification"]
        EQ["EqualizerController"]
        TRK["ActivePlayTracker"]

        PC <-->|"MediaBrowser IPC (SessionToken)"| MS
        MS --> EXO
        MS --> SES
        MS --> EQ
        MS --> TRK
    end

    subgraph DataLayer ["Data & Repository Layer"]
        MR["MusicRepository"]
        SR["StatsRepository"]
        PR["PlaylistRepository"]
        TR["TransferRepository / NavidromeUploadRepository"]
        PREF_REPO["UserPreferencesRepository"]
        
        VM --> MR
        VM --> SR
        VM --> PR
        VM --> TR
        VM --> PREF_REPO
    end

    subgraph ProviderLayer ["Media Providers (MediaProvider Interface)"]
        LMP["LocalMediaProvider (MediaStore)"]
        NMP["NavidromeProvider (Subsonic REST API)"]
        
        MR --> LMP
        MR --> NMP
        TR --> NMP
    end

    subgraph PersistenceLayer ["Persistence & Local Storage"]
        ROOM[("TerminusDatabase (Room SQLite)")]
        DS["DataStore (Preferences)"]
        COIL["Coil Image Cache (Album Art)"]
        
        MR --> ROOM
        SR --> ROOM
        PR --> ROOM
        TR --> ROOM
        TRK -->|"Flush completed listens"| ROOM
        PREF_REPO --> DS
        SCREENS --> COIL
    end

    subgraph BackgroundLayer ["Background Work (WorkManager)"]
        LSW["LibrarySyncWorker"]
        USW["UploadWorker (Upload & Download Transfers)"]
        PSW["PlaylistSyncWorker"]
        LSW_LIKE["LikeSyncWorker"]
        LCW["LyricsCacheWorker"]

        LSW --> MR
        USW --> TR
        PSW --> PR
        LSW_LIKE --> MR
        LCW --> MR
    end
```

---

## 2. Core Layers & Responsibilities

### Presentation Layer (`ui`)
- **Technology**: Jetpack Compose, Jetpack Navigation Compose, Material 3 + Custom HUD/CRT Design System.
- **Key Components**:
  - `MainActivity`: Single-activity container hosting the Compose UI tree and system bar configuration.
  - `ExternalPlaybackActivity`: Lightweight endpoint to handle external `ACTION_VIEW` audio intents.
  - `MainActivityViewModel`: Collects repositories, combines audio playback status, and exposes immutable UI state to composables.
  - `screens/`: Feature-scoped screen composables (`HomeScreen`, `NowPlayingScreen`, `LibraryScreen`, `SearchScreen`, `StatsScreen`, `UploadScreen`, `SettingsScreen`, etc.).

### Playback Layer (`playback`)
- **Technology**: AndroidX Media3 (ExoPlayer), Android MediaSession, AudioFocus.
- **Key Components**:
  - `MusicService`: Runs as a bound/foreground `MediaSessionService`. Manages the `ExoPlayer` instance, media notifications, lockscreen playback controls, audio focus loss/ducking, and crossfading.
  - `PlaybackController`: Singleton controller injected into UI ViewModels. Communicates with `MusicService` via AndroidX `MediaController` across process/thread boundaries. Exposes observable `nowPlaying: StateFlow<NowPlayingState>`.
  - `ActivePlayTracker`: Granular listening session timer that tracks elapsed milliseconds and flushes play events to `StatsRepository` when qualification thresholds are met.
  - `EqualizerController`: Direct interface with system audio session bands and gains.

### Data & Provider Layer (`data`)
- **Key Components**:
  - `MediaProvider`: Unified contract defining operations for loading tracks, albums, artists, playlists, search, and streaming URLs.
  - `LocalMediaProvider`: Queries Android's system `MediaStore` for local audio files.
  - `NavidromeProvider`: Communicates with a personal Navidrome server using the Subsonic API standard (REST/XML/JSON), parsing remote metadata, search results, and stream tokens.
  - `MusicRepository`: Facade consolidating data from all active providers and local Room cache.
  - `UserPreferencesRepository`: Backed by AndroidX DataStore for user settings (motion modes, crossfade duration, equalizer presets, Navidrome server credentials).

### Background Execution (`sync`)
- **Technology**: AndroidX WorkManager, Foreground Services.
- **Key Components**:
  - `LibrarySyncWorker`: Performs periodic or on-demand catalog synchronizations with configured providers.
  - `UploadWorker`: Handles queue-based bi-directional file transfers (uploading local audio to Navidrome, downloading server streams for offline listening) with foreground notifications.
  - `LikeSyncWorker` & `PlaylistSyncWorker`: Syncs favorite tracks and playlist structures with remote servers.
  - `LyricsCacheWorker`: Fetches and caches synchronized LRC lyrics.

---

## 3. Related Architecture Documentation

- [Playback Lifecycle & Audio Focus](file:///home/amns/Projects/Terminus/docs/PLAYBACK_LIFECYCLE.md)
- [Sync, Transfers & Offline Engine](file:///home/amns/Projects/Terminus/docs/SYNC_AND_TRANSFERS.md)
- [Database Schema & Entity Relationships](file:///home/amns/Projects/Terminus/docs/DATABASE_SCHEMA.md)
