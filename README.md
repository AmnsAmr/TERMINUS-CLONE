# Terminus — Navidrome music player for Android


────────────────────────────────────────────────

# TERMINUS

> **A Navidrome-connected Android music player with a terminal-inspired interface.**

This repository is an independently maintained fork of [Terminus by Souleymane Mountaga WONE (@Onesoulmoon)](https://github.com/Onesoulmoon/Terminus). It builds on his distinctive CRT, ASCII, and terminal-inspired player and extends it with a server-connected music experience.

The fork connects to Navidrome through its Subsonic-compatible API. It is being developed for listeners who want a Substreamer-like day-to-day server music workflow inside Terminus: browse and stream a server library, keep music available offline, and manage uploads and downloads from the app.

---

## Status

**Fork version: `0.2.0`** (Android app version)

Terminus combines Navidrome library browsing and streaming with local music playback, playlists, search, listening statistics, background playback, and a terminal-inspired interface. See [Features](#features) for the server and offline workflows available in this fork.

# Contents

* [What is Terminus?](#what-is-terminus)
* [Design Philosophy](#design-philosophy)
* [Features](#features)
* [Interface](#interface)
* [The Terminal Aesthetic](#the-terminal-aesthetic)
* [Music Playback](#music-playback)
* [Queue System](#queue-system)
* [Playlists](#playlists)
* [Connect to Navidrome](#connect-to-navidrome)
* [Navidrome Transfers](#navidrome-transfers)
* [Search](#search)
* [History and Statistics](#history-and-statistics)
* [Album Artwork](#album-artwork)
* [Audio Behavior](#audio-behavior)
* [Animations and Motion](#animations-and-motion)
* [Motion Modes](#motion-modes)
* [Settings](#settings)
* [Home-Screen Widget](#home-screen-widget)
* [Performance](#performance)
* [Architecture](#architecture)
* [Technology Stack](#technology-stack)
* [Android Compatibility](#android-compatibility)
* [Project Structure](#project-structure)
* [Building from Source](#building-from-source)
* [Installation](#installation)
* [Privacy](#privacy)
* [Design Decisions](#design-decisions)
* [Roadmap](#roadmap)
* [Contributing](#contributing)
* [Upstream Project and Credits](#upstream-project-and-credits)

---

# What is Terminus?

Terminus is an Android music player for both a local collection and a personal Navidrome server. Connect a server to browse and stream its library, or use the device library for local playback and uploads.

The app brings together:

* local music discovery
* Navidrome server library browsing and streaming
* album and artist organization
* music playback
* background playback
* queue management
* shuffle
* repeat
* playlists
* search
* playback history
* listening statistics
* album artwork
* audio focus handling
* persistent playback state
* home-screen controls
* terminal-inspired visualizations
* background uploads and downloads with transfer status

The interface is intentionally opinionated.

Terminus is not trying to be invisible.

It is trying to have an identity.

---

# Design Philosophy

Terminus was built around several principles.

## 1. The interface should feel like a system

The UI takes inspiration from computer terminals rather than conventional music applications.

Information is presented through:

* monospaced typography
* system-like labels
* ASCII elements
* technical metadata
* signal indicators
* compact information panels
* CRT-inspired effects

The objective is not to make the application complicated.

The objective is to make it **feel like software**.

---

## 2. Function before decoration

Visual effects should reinforce the interface instead of interfering with it.

Animations therefore have a functional role:

* playback state changes
* navigation
* signal activity
* audio visualization
* artwork transitions
* queue interactions
* system feedback

Animations are designed to be subtle enough to remain usable while still making the interface feel alive.

---

## 3. Personal music should remain personal

Terminus supports both music stored on the device and music served by a Navidrome installation. Server connection details are configured in the app; local playback remains available without a server.

---

## 4. Performance matters

A terminal aesthetic can easily become unnecessarily expensive if every visual element constantly redraws.

Terminus therefore treats performance as part of the design.

The application attempts to keep:

* CPU usage reasonable
* memory usage controlled
* animations efficient
* unnecessary recompositions minimized
* visual effects configurable
* background behavior predictable

The interface should look sophisticated without turning the music player into a resource-heavy visual demo.

---

# Features

## Core Playback

* Play / pause
* Previous / next track
* Seek
* Playback progress
* Background playback
* Persistent playback state
* Audio focus handling
* Queue management
* Shuffle
* Repeat
* Track deletion
* Album changes
* Automatic continuation of playback

---

## Library

Terminus brings locally available music and the connected Navidrome catalog into one library.

Depending on the metadata available in the files, the application can work with:

* tracks
* albums
* artists
* album artwork
* metadata
* playback information

The library is designed to remain simple and fast rather than attempting to become a full media-management suite.

## Navidrome Transfers

Terminus can upload local tracks to a configured Navidrome server and download server tracks to the device. Uploads started from the library, playlists, or upload screen and downloads use the same persistent background queue.

While a transfer is active, its Android notification shows the song name, queue position, and bytes transferred. When the file size is known, the notification also shows percentage progress; when it is unknown, progress remains indeterminate while still reporting transferred bytes. The notification includes an action to cancel transfers. Android notification settings may prevent notifications from appearing.

The **Stats** screen has separate upload and download queues. Queue entries show their state, per-song byte progress, attempts, and available error details. Failed transfers can be retried, and queued or active transfers can be cancelled. If Android or the network delays work, the queue reports that it is waiting or may be stalled instead of treating the delay as a confirmed transfer failure.

---

# Interface

Terminus uses a deliberately technical visual language.

The interface combines conventional Android interaction patterns with a custom terminal-inspired presentation.

Major UI elements include:

### Library views

The music library provides access to the user's local collection.

### Player interface

The player combines:

* album artwork
* playback controls
* track information
* playback progress
* terminal-style metadata
* animated signal elements

### Expanded player

The expanded player is where the Terminus identity becomes most apparent.

Album artwork can be surrounded or overlaid by terminal-inspired information and visual elements.

The interface can display:

```text
┌──────────────────────────────┐
│ TERMINUS // AUDIO SYSTEM     │
│                              │
│      [ ALBUM ARTWORK ]       │
│                              │
│  SIGNAL ▂▃▄▅▆▇█              │
│  TRACK  /  0047              │
│  STATUS / PLAYING            │
│                              │
│  ◀       ▮▮       ▶          │
└──────────────────────────────┘
```

The ASCII presentation is not simply decorative.

It establishes the application's visual identity.

---

# The Terminal Aesthetic

The visual language of Terminus is inspired by the physical and digital characteristics of older computer terminals.

## CRT-inspired presentation

The interface incorporates subtle CRT-like characteristics, including:

* scanline overlays
* screen pulses
* signal-like transitions
* technical HUD elements
* monospaced typography
* terminal-inspired spacing
* subtle visual noise
* data-particle effects

These effects are intentionally restrained.

The goal is **"modern software viewed through an old machine"**, not a literal CRT emulator.

---

## Typography

The design favors monospaced typography because it reinforces the terminal identity and creates consistent visual rhythm.

Potential typefaces include:

* IBM Plex Mono
* JetBrains Mono
* VT323

Typography is treated as part of the interface rather than merely as text styling.

---

# Music Playback

Playback is powered through Android's modern media playback architecture.

Terminus supports:

* local audio playback
* play / pause
* next / previous
* seeking
* background playback
* queue management
* shuffle
* repeat
* playback persistence
* audio focus behavior
* artwork updates
* playback state restoration

The player is designed to remain usable while the application is no longer in the foreground.

---

# Queue System

The queue system is an important part of the 1.4.0 release.

Terminus maintains playback context rather than treating every track as an isolated action.

Queue behavior includes:

* adding tracks
* changing playback order
* next / previous navigation
* shuffle
* repeat
* persistent queue state
* queue-aware playback
* track removal

Queue state is designed to survive normal application lifecycle events so that reopening Terminus does not unnecessarily reset the listening session.

---

# Shuffle and Repeat

Shuffle and repeat are treated as actual playback state rather than temporary UI states.

Their state can persist alongside the playback session.

This means Terminus can remember the user's chosen playback behavior instead of silently reverting to defaults.

---

# Playlists

Terminus supports local playlists and playlists from the connected server, bringing them into the same player workflow.

---

# Connect to Navidrome

In **Settings**, enter the base URL of your Navidrome server and your Navidrome username and password, then save the connection. Use the URL you normally use to reach the server, including any reverse-proxy path if applicable. Once connected, Terminus can load the server library and stream its tracks.

Local music remains available without a server connection. To upload local files or download server tracks for offline listening, use the transfer actions in the library, playlists, or upload screen.

---

# Search

The library includes search functionality for quickly locating music.

Search is designed to remain lightweight and direct:

```text
SEARCH
────────────────────────────
> daft punk
```

Rather than introducing an elaborate discovery system, Terminus focuses on helping users find music that already exists in their library.

---

# History and Statistics

Terminus keeps track of listening activity to provide a more useful picture of how the user interacts with their library.

Playback history can be used to understand:

* recently played music
* listening patterns
* frequently played tracks
* accumulated playback activity

Statistics are intentionally secondary to playback.

They exist to enrich the player rather than turn it into a social platform.

---

# Album Artwork

Album artwork is treated as a central part of the player experience.

The expanded player combines artwork with the terminal/HUD layer to create a visual relationship between:

**music + artwork + system interface**

Artwork transitions are also incorporated into the animation system so that changing albums feels intentional rather than instantaneous.

Artwork handling was also included in the 1.4.0 playback polish pass.

---

# Audio Behavior

Terminus handles Android audio behavior with the goal of behaving like a proper media application.

This includes:

* audio focus
* playback state
* background playback
* media session behavior
* transition between tracks
* restoration of playback state

Audio focus handling was specifically addressed during the 1.4.0 finalization pass.

---

# Animations and Motion

The 1.4.0 interface contains a dedicated motion foundation rather than a collection of unrelated animations.

The animation system includes:

### Spring-based controls

Controls can use spring-like motion to make interaction feel less mechanical while retaining the terminal aesthetic.

### Play / pause transitions

Playback controls visually transition between states instead of simply swapping icons.

### Audio-monitor bars

Signal bars respond visually to playback activity.

Example:

```text
SIGNAL

▁▂▃▄▅▆▇█
```

### Signal HUD

Technical information and playback state can be represented through animated HUD elements.

### CRT scan and pulse

Subtle scanline and pulse effects provide the impression of a physical display.

### Floating data particles

Small background particles provide additional depth without becoming the primary visual element.

### Playback-art transitions

Album artwork transitions are synchronized with playback changes.

### Navigation transitions

Moving between sections uses controlled transitions instead of abrupt screen replacement.

---

# Motion Modes

Because animation should never come at the cost of usability or battery life, Terminus includes multiple motion levels.

## FULL

The complete Terminus visual experience.

Includes:

* full transitions
* signal animations
* CRT effects
* particles
* animated controls
* artwork transitions
* HUD motion

Recommended for users who want the full visual identity.

---

## BALANCED

A compromise between visual identity and resource usage.

Reduces some of the more expensive or continuous effects while retaining the important interaction animations.

Recommended for everyday use.

---

## MINIMAL

A low-motion mode.

Reduces visual effects and prioritizes:

* responsiveness
* battery efficiency
* low rendering overhead
* accessibility
* distraction-free playback

The application remains visually recognizable as Terminus without requiring the full animation layer.

---

# Settings

The settings system was cleaned up during the final 1.4.0 pass.

The goal was to remove unnecessary clutter and keep configuration focused on options that actually affect the experience.

Settings can control the application's behavior and visual presentation without turning the settings screen into another complicated dashboard.

Particular attention was given to motion configuration so users can choose between:

```text
FULL
BALANCED
MINIMAL
```

This provides a direct way to balance aesthetics against device resources.

---

# Home-Screen Widget

Terminus includes a home-screen music widget.

The widget provides quick access to playback without requiring the user to open the application.

It is intended to preserve the Terminus visual language while remaining functional as a conventional Android widget.

---

# Android Launcher Icon

The application icon was also finalized as part of the 1.4.0 polish pass.

The icon follows the same visual language as the application:

* dark matte appearance
* CRT-inspired form
* rounded display
* terminal-inspired typography
* scanline influence
* block/cursor visual language
* ASCII-inspired identity

The icon is intended to make Terminus recognizable before the application is even opened.

---

# Performance

Performance was treated as a finalization requirement rather than an optional enhancement.

The application combines several potentially expensive visual systems:

* animated artwork
* signal visualization
* CRT effects
* particles
* transitions
* dynamic UI state
* playback updates

Without control, these effects could unnecessarily increase CPU usage or trigger excessive recomposition.

The final performance pass therefore focused on reducing unnecessary work while preserving the visual character of the application.

The guiding rule is:

> **Animate what communicates information. Do not animate everything simply because it can move.**

The motion modes provide another layer of control for devices or users that prefer reduced visual activity.

---

# Architecture

Terminus is a native Android application built using modern Android development practices.

The application separates major responsibilities between:

* UI
* playback
* persistence
* media/library management
* application state
* configuration

This allows the visual layer to evolve without requiring the playback system to be rewritten.

---

# Technology Stack

## Language

**Kotlin**

Kotlin is used throughout the Android application.

---

## UI

**Jetpack Compose**

Compose is used for the application's interface and provides the foundation for the custom terminal/HUD visual system.

This makes it possible to build the highly dynamic interface while keeping UI state declarative.

---

## Media Playback

**AndroidX Media3 / ExoPlayer**

Media3 provides the playback infrastructure required for:

* local audio
* media sessions
* background playback
* audio focus
* playback state
* queue management
* streaming from a configured Navidrome server

---

## Database / Persistence

**Room**

Room is used for persistent application data such as:

* playlists
* history
* statistics
* cached server library data
* persistent upload and download queue state
* playback-related information
* persistent application state

---

## Dependency Injection

**Hilt**

Hilt provides dependency injection and helps keep application components modular.

---

# Android Compatibility

The current application targets modern Android devices while maintaining compatibility with Android 10 and newer.

**Minimum SDK: Android 10 / API 29**

The application is therefore designed for:

```text
Android 10+
API 29+
```

---

# Project Structure

The exact project structure may evolve as development continues, but the architecture broadly follows the separation of UI, media, persistence, and application logic.

A simplified conceptual structure is:

```text
Terminus/
│
├── app/
│   ├── ui/
│   │   ├── screens/
│   │   ├── components/
│   │   ├── player/
│   │   ├── library/
│   │   └── settings/
│   │
│   ├── media/
│   │   ├── playback/
│   │   ├── queue/
│   │   └── session/
│   │
│   ├── data/
│   │   ├── database/
│   │   ├── entities/
│   │   └── repositories/
│   │
│   ├── model/
│   │
│   ├── services/
│   │
│   └── ...
│
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

The actual repository structure should always be considered authoritative.

---

# Building from Source

## Requirements

You will need:

* Android Studio
* Android SDK
* JDK compatible with the project's Gradle configuration
* Android SDK Platform 29 or newer
* Gradle wrapper included with the project

Clone this fork:

```bash
git clone https://github.com/AmnsAmr/TERMINUS-CLONE.git
```

Enter the project directory:

```bash
cd TERMINUS-CLONE
```

Then open the project in Android Studio.

Allow Gradle to synchronize the project and install any required Android SDK components.

---

# Building the APK

Using the Gradle wrapper:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
gradlew.bat assembleDebug
```

The generated debug APK will normally be located under:

```text
app/build/outputs/apk/
```

For a release build, configure the appropriate signing credentials and release configuration before generating the APK.

---

# Installation

A debug APK can be installed on a compatible Android device using:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, the APK can be installed directly through Android after transferring it to the device.

---

# Permissions

Terminus needs access to audio that you choose to scan or upload. Android's system file picker is used for selecting files and folders, and local audio access follows the permission model of the Android version in use.

The exact permission behavior depends on the Android version and the application's manifest configuration.

Transfer notifications are used to show background upload and download progress. On Android 13 and newer, allow notifications if you want to see transfer updates in the notification shade. Transfers can still be monitored in the app's **Stats** screen when notifications are disabled.

---

# Privacy

Local files stay on the device unless the user chooses to upload them. When a Navidrome server is configured, Terminus sends the requests needed to browse and stream its library and to perform user-requested sync and transfer actions.

The fundamental workflow is:

```text
LOCAL FILES ── user-selected upload ──> NAVIDROME
     │                                      │
     └──────────── TERMINUS <── stream / download
```

Server playback and transfers use the Navidrome URL and credentials configured in the app.

---

# Why Terminus Exists

There are countless music players.

There are also countless terminal-inspired applications.

Terminus combines the two.

The project started from the idea that a music player could have a stronger personality without sacrificing usability.

Instead of hiding the technical nature of the software, Terminus makes it part of the experience.

The player can tell you:

```text
TRACK     : CURRENT
STATUS    : PLAYING
SIGNAL    : ACTIVE
SYSTEM    : ONLINE
AUDIO     : STREAMING
```

It turns playback into a small visual system.

---

# Design Language

The Terminus identity can be summarized as:

```text
┌─────────────────────────────────┐
│                                 │
│          TERMINUS               │
│                                 │
│  SERVER + LOCAL // CONNECTED    │
│                                 │
│   ░▒▓ SIGNAL MONITOR ▓▒░        │
│                                 │
│   CRT       ASCII       HUD     │
│   MONO      DATA        MOTION  │
│                                 │
└─────────────────────────────────┘
```

The design deliberately sits between:

**retro computing**

and

**modern Android software**.

It is not intended to perfectly emulate an old terminal.

It is an interpretation of one.

---

# What Changed in 1.4.0

Version 1.4.0 represents the final major polish phase of the current Terminus development cycle.

### Playback

* Improved core playback behavior
* Improved queue handling
* Persistent queue state
* Persistent shuffle state
* Persistent repeat state
* Improved previous/next behavior
* Improved album transitions
* Improved audio focus handling
* Track deletion support
* Improved playback restoration

### Visual system

* Spring-based controls
* Play/pause transitions
* Animated audio-monitor bars
* Signal HUD
* CRT scanline effects
* CRT pulse effects
* Floating data particles
* Playback-art transitions
* Navigation transitions

### Motion

* FULL motion mode
* BALANCED motion mode
* MINIMAL motion mode

### Application polish

* Final launcher icon integration
* Home-screen widget
* Settings cleanup
* UI consistency pass
* Performance pass
* Reduced unnecessary visual work
* Improved overall responsiveness

The objective of 1.4.0 was not to simply add more features.

It was to make the existing system feel **finished**.

---

# Development Philosophy

Terminus is intentionally experimental.

It is a personal project built to explore:

* Android development
* Kotlin
* Jetpack Compose
* media playback
* UI architecture
* motion design
* visual identity
* performance optimization
* local-first applications

Some design decisions may therefore be unconventional.

That is intentional.

The project is as much an exploration of interface design as it is a music player.

---

# Roadmap

Future development may include improvements in areas such as:

* additional audio controls
* more visualization modes
* deeper metadata handling
* additional library organization
* expanded playlist functionality
* improved artwork management
* additional widget capabilities
* further performance optimization
* additional terminal themes
* accessibility improvements
* continued UI refinement

The roadmap is intentionally flexible.

Terminus prioritizes **quality and coherence over feature quantity**.

---

# Contributing

Contributions, suggestions, bug reports, and design feedback are welcome.

If you find a bug:

1. Check whether it has already been reported.
2. Provide the Android version and device information.
3. Describe the expected behavior.
4. Describe what actually happened.
5. Include relevant logs or screenshots when possible.

For feature requests, explain the problem the feature would solve rather than only describing the feature itself.

The goal is to keep Terminus coherent rather than turning it into a collection of unrelated features.

---

# Bug Reports

When reporting a problem, please include:

```text
Device:
Android version:
Terminus version:
Problem:
Steps to reproduce:
Expected behavior:
Actual behavior:
Additional information:
```

Screenshots and logs are especially useful for UI or playback problems.

---

# Upstream Project and Credits

This fork is based on [Terminus](https://github.com/Onesoulmoon/Terminus), created by **Souleymane Mountaga WONE** ([@Onesoulmoon](https://github.com/Onesoulmoon)). The original project established the Terminus name, visual direction, and offline player foundation. This repository develops that work independently and adds a Navidrome-connected music workflow.

The Substreamer comparison describes the kind of straightforward Navidrome listening experience this fork aims to provide. Terminus is an independent project and is not affiliated with Substreamer or its developers.

---

# Project

**Terminus — Navidrome fork**

An independently maintained Android music player combining Navidrome streaming and transfer workflows with the terminal-inspired design of the upstream Terminus project.

```text
╔══════════════════════════════════════╗
║                                      ║
║              TERMINUS                ║
║                                      ║
║       NAVIDROME // ONLINE            ║
║              SYSTEM                  ║
║                                      ║
║       PLAY       ▮▮       NEXT       ║
║                                      ║
║       ░▒▓ SIGNAL ACTIVE ▓▒░          ║
║                                      ║
╚══════════════════════════════════════╝
```

**Music is data.**

**Terminus is the interface.**


- Local Database: Room Persistence Library (Entities: Song, Playlist, LikedSong, PlayEvent)

- Audio Engine: Android MediaSession / Foreground Service API

- Architecture Pattern: MVVM (Model-View-ViewModel) + Unidirectional Data Flow (UDF)
