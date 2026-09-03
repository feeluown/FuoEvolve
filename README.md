# FuoEvolve

[![Stable release](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster)
[![PR Tests](https://github.com/feeluown/FuoEvolve/actions/workflows/pr-tests.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/pr-tests.yml)
[![Release](https://github.com/feeluown/FuoEvolve/actions/workflows/release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/release.yml)
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
| Canary | [Latest master Android build](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) | Signed release APK built from the latest successful `master` workflow. |

## Highlights

- 🎵 **Multiple music sources in one app** — use NetEase Cloud Music, QQ Music, Bilibili, and YouTube Music side by side, and choose which sources are enabled and preferred.
- 🧭 **Rich discovery** — browse daily recommendations, private radio, charts, playlist and artist collections, new releases, MV collections, music styles, and other source-specific content.
- 🔎 **Unified search** — search enabled online sources and local music, with dedicated result types where available and persistent recent-search history for quick reuse.
- ▶️ **Resilient playback** — manage the queue, shuffle/repeat, Up Next, seeking, multipart content, audio and video playback, use a sleep timer, and resume the active queue and playback state after an Android process restart.
- 🎤 **Rich and flexible lyrics** — synchronized, translated, romanized, and word-level lyrics where providers support them; manually associate and remember lyrics for tracks whose source does not provide lyrics.
- 📱 **System and device lyric output** — publish timed lyrics to supported ColorOS lock screens, Lyricon status-bar lyrics, and supported BYD instrument clusters on Android.
- 🔁 **Smarter source replacement** — when a track is unavailable, automatically find a close match from another enabled source with recording-aware scoring while preserving the original song context and lyrics.
- ❤️ **Manage online libraries** — where the provider allows it, favorite or unfavorite playlists, artists, and albums, and manage owned NetEase/QQ Music playlists from inside FuoEvolve.
- 💽 **Local music and offline listening** — browse and edit local music, create portable local playlists, download supported online tracks, resume interrupted downloads, and keep downloaded content indexed in the local library.
- 🔐 **Portable provider credentials** — Android can export encrypted credential backups for all or individual providers and restore them later without replacing normal on-device secure storage.
- 🎙️ **Audio recognition** — identify a song from the microphone and jump directly to search or song details.
- 🔗 **Share into FuoEvolve** — on Android, share supported NetEase, QQ Music, Bilibili, YouTube, or YouTube Music links to the app to open the matching content; unsupported share text can fall back to search.
- 🎨 **Material 3 Expressive UI** — light/dark themes, dynamic color, cover-inspired player colors, smooth transitions, and layouts that adapt across phone and larger screens.

## Music Sources

FuoEvolve currently includes four online sources. Available content can vary by source, region, account state, and upstream service behavior.

| Source | What you can explore |
| --- | --- |
| **NetEase Cloud Music** | Daily songs, recommended and new songs, private FM, charts, playlist square, artist square, MV square, music styles, favorite songs, cloud songs, playlists, artists, and albums, with supported playlist and favorite mutations. |
| **QQ Music** | Daily songs, recommendations, private FM, charts, playlist square, artist square, new albums, MV square, multi-type search, owned/favorite playlists, favorite albums, followed artists, and supported playlist/favorite mutations. |
| **Bilibili** | Music/video search and playback, personalized recommendations, dynamic videos, weekly must-watch, watch later, viewing history, followed creators and uploads, favorites, collected bangumi/films, and multipart playback. |
| **YouTube Music** | Song/video search, recommendations and library content, playlists, artists, albums, charts, lyrics, similar-track radio, and video playback where available. |

Sources can be enabled, disabled, reordered, and signed in from Settings. NetEase, QQ Music, and Bilibili use account cookies. YouTube Music supports Google authorization as well as imported account headers/cookies.

## Playback and Lyrics

FuoEvolve is designed for both everyday listening and mixed music/video libraries. You can manage the play queue, use shuffle or repeat, add tracks to Up Next, seek through playback, open artist or album details directly from the player, and start a sleep timer that stops playback after a chosen duration or at the end of the current track.

On Android, the playback session and durable queue cooperate so a process restart can restore the active track, queue, shuffle/repeat state, multipart position, and playback progress. Explicit user selections remain separate from resume behavior, so choosing a new song or playlist does not accidentally revive an older paused session.

MV and video playback has a dedicated viewing experience with transport controls, full-screen playback, orientation handling, and support for multipart Bilibili videos. Smart replacement treats multipart replacement candidates as a single replacement track instead of unexpectedly advancing through their parts.

Lyrics support includes synchronized lyrics, translations, romanized lines, and rich word-level timing from NetEase and QQ Music where available. For sources without usable lyrics, you can manually search for another track's lyrics, associate them with the current track, and keep that choice for future playback. Bilibili uses the video's BGM title as the preferred lyric search keyword when the upstream metadata provides one, falling back to the video title.

Android can also publish timed lyrics outside the player: supported ColorOS devices can show lock-screen live lyrics, Lyricon can provide status-bar lyrics, and supported BYD vehicles can receive instrument-cluster lyrics.

## Discovery and Personal Library

The home and library experience follows the content each source actually provides instead of forcing every source into the same shape. Provider sections load incrementally, keeping large discovery and library views responsive while additional content is fetched.

NetEase and QQ Music expose broader discovery areas such as charts, playlist collections, artist browsing, MV browsing, categories, and filters. NetEase also includes music-style browsing, while QQ Music includes new album discovery and expanded Mine content such as favorite playlists, favorite albums, and followed artists.

Where supported, provider detail pages expose the resource's current favorite state and let you favorite or unfavorite playlists, artists, and albums. Owned NetEase and QQ Music playlists expose only the mutations they actually allow; QQ Music supports creating and deleting owned playlists, and supported playlists can add or remove tracks.

Bilibili focuses on its own content model: personalized and dynamic videos, weekly recommendations, Watch Later, viewing history, followed creators, creator uploads, favorites, and collected bangumi/films.

Your library also surfaces frequently played playlists so commonly used collections are easier to return to. Search keeps recent queries newest-first for quick reuse and lets you remove individual history entries.

## Local Music, Playlists, and Downloads

Local music can be scanned into the app and browsed by all tracks, artist, album, or directory. Scan rules can be adjusted to include specific folders or ignore very short audio files. The local index tracks media changes and stored lyrics so large libraries can refresh without rebuilding unchanged entries unnecessarily.

Track title, artist, and album information can be edited locally. FuoEvolve can also use enabled online sources to help find better metadata and lyrics, and manually associated lyrics remain available across later playback.

Local playlists can contain local, downloaded, and supported online tracks. They can be created and managed in the app, imported from files, and shared as playlist files.

Supported online tracks can be downloaded for offline listening. Download state and resume metadata are persisted so interrupted transfers can continue, and completed downloads are integrated into the offline/local library for normal playback and browsing.

## Audio Recognition

Open audio recognition from the microphone action in Search. The app records only what is needed to generate an audio fingerprint, keeps the captured audio in memory, and sends the fingerprint to the recognition service rather than uploading the original recording.

Recognized songs can be searched immediately or opened in their NetEase details. The latest recognition result remains available when navigating between Search and detail pages, and unsuccessful recognition can be retried.

## Personalization and Settings

FuoEvolve lets you tune the experience without requiring deep configuration. Common options include:

- online source enablement, priority, and login management;
- encrypted provider credential backup and restore on Android, including per-provider export;
- separate Wi-Fi and cellular audio-quality preferences;
- smart replacement providers, behavior, and matching strictness for unavailable tracks;
- playback behavior, including whether another app starting audio should pause FuoEvolve;
- system, light, or dark appearance, dynamic color, preset colors, and cover-inspired player colors on a dedicated appearance settings page;
- lyric font size and external lyric output such as supported ColorOS, Lyricon, and BYD integrations;
- local music scan folders and minimum track duration;
- audio and image cache limits.

## iOS Status

iOS support is experimental. The project includes an iOS app and CI builds for development, and a growing set of shared features such as browsing, playback, MV controls, and audio recognition are available there.

iOS is not currently published as a GitHub Release or App Store build, so it should not yet be considered an end-user supported platform.

## Development

FuoEvolve uses Kotlin Multiplatform and Compose Multiplatform to share most application code across platforms. Android uses AndroidX Media3 for playback. The codebase is split into explicit feature, playback, provider, persistence, and platform boundaries so shared UI does not own provider or playback runtime responsibilities.

Requirements:

- JDK 17 or newer;
- Android Studio or Android command-line tools for Android builds;
- Xcode on macOS for experimental iOS builds.

Build and install the Android debug app:

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

Run shared tests and Android lint:

```bash
./gradlew :shared:allTests
./gradlew :androidApp:lint :shared:lint
```

Main Gradle module groups:

- `core:model` — shared application/domain models;
- `feature:*` — feature ownership for search, recognition, local/offline libraries, provider browsing/auth/details, settings, onboarding, and home;
- `playback:api` / `playback:runtime` — playback contracts, session orchestration, queue/lyrics/timer behavior, and platform integration boundaries;
- `provider:api` / `provider:runtime` plus `provider:netease`, `provider:qqmusic`, `provider:bilibili`, and `provider:ytmusic` — provider contracts, runtime plumbing, and source-specific implementations;
- `persistence:settings` — persisted shared settings and user choices;
- `shared` — shared Compose application/UI integration;
- `androidApp` — Android application and platform services;
- `iosApp/FuoEvolve` — experimental iOS application;
- `.github/workflows` — PR tests, master Canary builds, release automation, and reusable platform validation/packaging workflows.

## License

FuoEvolve is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
