# FuoEvolve

[![Stable release](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/android-apk.yml?branch=master&label=canary)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![iOS CI](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/ios-debug-app.yml?branch=master&label=iOS%20CI)](https://github.com/feeluown/FuoEvolve/actions/workflows/ios-debug-app.yml?query=branch%3Amaster)
[![Android Release](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml)
[![Coverage](https://codecov.io/gh/feeluown/FuoEvolve/branch/master/graph/badge.svg)](https://app.codecov.io/gh/feeluown/FuoEvolve)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Experimental-FF9500?logo=apple&logoColor=white)

[中文](README.zh-CN.md) | English

FuoEvolve is an open-source music player built around the [FeelUOwn](https://github.com/feeluown/FeelUOwn) ecosystem. It brings multiple online music services, local music, playlists, downloads, lyrics, and video into one app with a modern Material 3 experience.

Android is the primary supported platform. iOS builds are available for experimentation and development, but are not currently released for end users.

## Download

| Channel | Link | Notes |
| --- | --- | --- |
| Stable | [Latest GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) | Signed multi-ABI Android APK for `arm64-v8a` and `x86_64`. |
| F-Droid | [Official self-hosted repository](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | Keeps the latest stable releases and updates automatically after GitHub Releases. |
| Canary | [Latest master Android build](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster) | Signed release APK built from the latest successful `master` workflow. |

## Highlights

- 🎵 **Multiple music sources in one app** — use NetEase Cloud Music, QQ Music, Bilibili, and YouTube Music side by side, and choose which sources are enabled and preferred.
- 🧭 **Rich discovery** — browse daily recommendations, private radio, charts, playlist and artist collections, new releases, MV collections, music styles, and other source-specific content.
- 🔎 **Unified search** — search enabled online sources and local music, with dedicated results for songs, artists, albums, playlists, and videos where available.
- ▶️ **Complete playback experience** — queue management, shuffle and repeat, up next, seeking, multipart content, audio playback, and a full-screen MV/video player with playback controls.
- 🎤 **Better lyrics** — synchronized lyrics, translations, NetEase word-by-word karaoke highlighting, and ColorOS lock-screen live lyrics on supported OPPO/OnePlus devices.
- 🔁 **Smart source replacement** — when a track is unavailable, FuoEvolve can automatically look for a close match from another enabled source while keeping the original song information and lyrics.
- 💽 **Local music and playlists** — browse local music by artist, album, or directory, edit metadata, create local playlists, and import or share playlist files.
- ⬇️ **Downloads for offline listening** — download supported online tracks and play them locally from the app.
- 🎙️ **Audio recognition** — identify a song from the microphone and jump directly to search or song details.
- 🔗 **Share into FuoEvolve** — on Android, share supported NetEase, QQ Music, Bilibili, YouTube, or YouTube Music links to the app to open the matching content; unsupported share text can fall back to search.
- 🎨 **Material 3 Expressive UI** — light/dark themes, dynamic color, cover-inspired player colors, smooth transitions, and layouts that adapt across phone and larger screens.

## Music Sources

FuoEvolve currently includes four online sources. Available content can vary by source, region, account state, and upstream service behavior.

| Source | What you can explore |
| --- | --- |
| **NetEase Cloud Music** | Daily songs, recommended and new songs, private FM, charts, playlist square, artist square, MV square, music styles, favorite songs, cloud songs, playlists, artists, and albums. |
| **QQ Music** | Daily songs, recommendations, private FM, charts, playlist square, artist square, new albums, MV square, multi-type search, user playlists, favorites, artists, and albums. |
| **Bilibili** | Music/video search and playback, personalized recommendations, dynamic videos, weekly must-watch, watch later, viewing history, followed creators and uploads, favorites, and collected bangumi/films. |
| **YouTube Music** | Song and video search, recommendations and library content, playlists, artists, albums, charts, and video playback where available. |

Sources can be enabled, disabled, reordered, and signed in from Settings. NetEase, QQ Music, and Bilibili use account cookies. YouTube Music supports Google authorization as well as imported account headers/cookies.

## Playback and Lyrics

FuoEvolve is designed for both everyday listening and mixed music/video libraries. You can manage the play queue, use shuffle or repeat, add tracks to Up Next, seek through playback, and open artist or album details directly from the player.

MV and video playback has a dedicated viewing experience with transport controls, full-screen playback, orientation handling, and support for multipart Bilibili videos.

Lyrics support includes classic synchronized lyrics, translated lines, and NetEase word-level lyrics with karaoke-style progress highlighting. On supported ColorOS devices, timed lyrics can also appear on the lock screen while music is playing.

## Discovery and Personal Library

The home and library experience follows the content each source actually provides instead of forcing every source into the same shape.

NetEase and QQ Music expose broader discovery areas such as charts, playlist collections, artist browsing, MV browsing, categories, and filters. NetEase also includes music-style browsing, while QQ Music includes new album discovery.

Bilibili focuses on its own content model: personalized and dynamic videos, weekly recommendations, Watch Later, viewing history, followed creators, creator uploads, favorites, and collected bangumi/films.

Your library also surfaces frequently played playlists so commonly used collections are easier to return to.

## Local Music, Playlists, and Downloads

Local music can be scanned into the app and browsed by all tracks, artist, album, or directory. Scan rules can be adjusted to include specific folders or ignore very short audio files.

Track title, artist, and album information can be edited locally. FuoEvolve can also use enabled online sources to help find better metadata and lyrics.

Local playlists can contain local, downloaded, and supported online tracks. They can be created and managed in the app, imported from files, and shared as playlist files. Downloaded tracks remain available for local playback inside FuoEvolve.

## Audio Recognition

Open audio recognition from the microphone action in Search. The app records only what is needed to generate an audio fingerprint, keeps the captured audio in memory, and sends the fingerprint to the recognition service rather than uploading the original recording.

Recognized songs can be searched immediately or opened in their NetEase details. The latest recognition result remains available when navigating between Search and detail pages, and unsuccessful recognition can be retried.

## Personalization and Settings

FuoEvolve lets you tune the experience without requiring deep configuration. Common options include:

- online source enablement, priority, and login management;
- separate Wi-Fi and cellular audio-quality preferences;
- smart replacement behavior for unavailable tracks;
- playback behavior, including whether another app starting audio should pause FuoEvolve;
- system, light, or dark appearance, dynamic color, preset colors, and cover-inspired player colors;
- lyric font size and display preferences;
- local music scan folders and minimum track duration;
- audio and image cache limits.

## iOS Status

iOS support is experimental. The project includes an iOS app and CI builds for development, and a growing set of shared features such as browsing, playback, MV controls, and audio recognition are available there.

iOS is not currently published as a GitHub Release or App Store build, so it should not yet be considered an end-user supported platform.

## Development

FuoEvolve uses Kotlin Multiplatform and Compose Multiplatform to share most application code across platforms. Android uses AndroidX Media3 for playback.

Requirements:

- JDK 17 or newer;
- Android Studio or Android command-line tools for Android builds;
- Xcode on macOS for experimental iOS builds.

Build and install the Android debug app:

```bash
./gradlew :androidApp:assembleStandardDebug
./gradlew :androidApp:assembleSmartDebug
./gradlew :androidApp:installStandardDebug
./gradlew :androidApp:installSmartDebug
```

Run shared tests and Android lint:

```bash
./gradlew :shared:allTests
./gradlew :androidApp:lint :shared:lint
```

Main project areas:

- `shared` — shared UI, application state, music-source integrations, and common tests;
- `androidApp` — Android application and platform playback integration;
- `iosApp/FuoEvolve` — experimental iOS application;
- `.github/workflows` — Android release/build automation and iOS development builds.

## License

FuoEvolve is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
