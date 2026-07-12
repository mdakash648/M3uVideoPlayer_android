# M3uVideoPlayer

A powerful and modern IPTV player for Android, designed for both mobile devices and Android TV / Firestick. Built with a clean architecture and powered by the LibVLC engine, it offers a seamless streaming experience for M3U playlists and Xtream Codes.

## 🚀 Key Features

### 📺 Advanced Playback (LibVLC Engine)

- **High Performance:** Support for 4K/8K video with hardware acceleration.
- **Audio Boost:** Increase volume up to 200% for quiet streams.
- **Gesture Controls:** Intuitive vertical swipes for brightness (left) and volume (right).
- **Quick Seek:** Double-tap on screen edges to jump ±10 seconds.
- **Playback Speed:** Adjust speed from 0.5x to 2.0x.
- **Multi-track Support:** Easily switch between different audio tracks and subtitles.
- **Picture-in-Picture (PiP):** Continue watching while using other apps.
- **Background Audio:** "Play as Audio" mode with a foreground service for uninterrupted listening.
- **Catch-up Support:** Integrated support for archive/catch-up streams (Flussonic and others).
- **Lock Mode:** Prevent accidental touches during playback.

### 📂 Playlist & Channel Management

- **M3U & Xtream Codes:** Full support for both playlist formats.
- **Background Sync:** Automatic playlist updates using WorkManager with configurable intervals (On start, 6h, 12h, etc.).
- **Smart Browsing:** Browse by groups/folders or see all channels at once.
- **Fuzzy Search:** Quickly find any channel across all your playlists.
- **Multiple View Modes:** Choose from List, Grid, Title Only, or Poster views.
- **Favorites:** Mark your most-watched channels for quick access.
- **Sorting:** Sort channels and folders by A-Z, Z-A, or original playlist order.

### 🏠 Optimized for All Screens

- **Dual UI:** Dedicated interfaces for Mobile/Tablet and Android TV/Firestick.
- **TV Native Experience:** Leanback-based browsing for effortless navigation with a remote.
- **Full D-pad Support:** Every part of the app is fully navigable via remote control or keyboard.

## 🛠 Tech Stack

- **Architecture:** MVVM + Clean Architecture
- **Dependency Injection:** Hilt
- **Database:** Room (Offline caching of playlists and channels)
- **Networking:** Retrofit (Xtream Codes API)
- **Image Loading:** Coil
- **Preferences:** DataStore
- **Background Tasks:** WorkManager

## 📦 Download & Installation

You can find the latest APK in the [Releases](github.com/mdakash648/M3uVideoPlayer_android/releases/download/v1.0.1/M3uVideoPlayer.apk) section.

---

_Note: This application does not provide any content. You must provide your own M3U playlist or Xtream Codes credentials._
