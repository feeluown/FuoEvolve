# FuoEvolve

[![Stable release](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/android-apk.yml?branch=master&label=canary)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![iOS CI](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/ios-debug-app.yml?branch=master&label=iOS%20CI)](https://github.com/feeluown/FuoEvolve/actions/workflows/ios-debug-app.yml?query=branch%3Amaster)
[![Android Release](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Experimental-FF9500?logo=apple&logoColor=white)

[中文](README.zh-CN.md) | English

FuoEvolve is an open-source music player based on the
[FeelUOwn](https://github.com/feeluown/FeelUOwn) ecosystem. Android is usable
today. Experimental iOS build support is available, but iOS is not released.

The project uses Kotlin Multiplatform and Compose Multiplatform for shared UI,
state, player contracts, and provider implementations. The provider layer is
implemented in Kotlin with Ktor and uses AndroidX Media3 for audio and video
playback on Android.

## Download

| Channel | Link | Packages |
| --- | --- | --- |
| Stable | [Latest GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) | One signed multi-ABI release APK containing `arm64-v8a` and `x86_64`. |
| F-Droid | [Official self-hosted repository](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | The five latest stable multi-ABI releases, updated automatically after each GitHub Release. |
| Canary | [Latest master Android APK workflow](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster) | Artifacts from the newest successful master build: signed debug APK and signed multi-ABI release APK. |

iOS builds are experimental debug artifacts only. They are not published as
GitHub Releases or supported for end-user installation.

## Highlights

- 🎵 FeelUOwn-based online music provider integration on Android.
- 🔎 Search across enabled providers and local music, with provider filtering.
- 🧭 Typed provider search tabs for songs, artists, albums, playlists, and videos
  where the upstream provider supports those result types.
- 🏠 Provider home sections for recommendations, exploration, user playlists, and
  favorites.
- ▶️ Media3 audio playback, video/MV playback, queue management, shuffle, repeat,
  up-next, multi-part tracks, covers, LRC lyrics, and artist/album detail
  navigation from the full player.
- 🎙️ Audio recognition on Android and experimental iOS builds, with recognized
  song results, direct search, and NetEase detail links.
- 📚 Playlist playback starts from loaded tracks and incrementally loads the
  remaining tracks in the background, including large shuffled playlists.
- 🔁 Smart replacement for unavailable tracks, with configurable provider pool,
  score threshold, metadata policy, and lyric policy.
- ⬇️ Downloads, app-private lyrics, local music database, local metadata edits, and
  provider-assisted metadata/lyric lookup.
- 🔗 Direct system sharing with App Link-friendly share URLs.
- ⚙️ Runtime settings for providers, login, quality, playback behavior, local scan
  filters, cache limits, lyrics, and theme.

## Audio Recognition

Open audio recognition from the microphone action in Search. Android and
experimental iOS builds request microphone permission, keep the captured audio
in memory while generating a fingerprint, and send only the audio fingerprint
to the recognition service. The app displays recognized songs and lets you search
for them or open their NetEase details. Recognition results remain available when
you return from Search or a detail page; an unsuccessful run ends with a retry
option.

## Provider Support

Provider implementations currently bundled in the shared Kotlin module:

| Provider | Implementation | Default | Login modes |
| --- | --- | --- | --- |
| NetEase Cloud Music | `NeteaseProvider` | Enabled | Cookie |
| QQ Music | `QQMusicProvider` | Available in Settings | Cookie |
| Bilibili | `BilibiliProvider` | Available in Settings | Cookie |
| YouTube Music | `YtMusicProvider` | Available in Settings | OAuth (TV) / Headers |

The app loads NetEase by default. QQ Music, Bilibili, and YouTube Music are
packaged and can be enabled, disabled, or reordered from Settings.

YouTube Music supports two login modes:

1. **Google OAuth (TV / Limited Input device-code)** — same flow as
   [ytmusicapi OAuth](https://ytmusicapi.readthedocs.io/en/stable/setup/oauth.html).
   Create a Google Cloud OAuth client of type **TVs and Limited Input devices**,
   enable the YouTube Data API, enter or import the Console `client_secret_*.json`
   in Settings, then tap **Sign in with Google (TV)** and complete the browser
   verification code. You can also import an `oauth.json` produced by
   `ytmusicapi oauth` (client credentials are still required for refresh).
2. **Headers / Cookie** — import `ytmusic_header.json` or paste Authorization +
   Cookie manually.

Local `oauth.json` and `client_secret*.json` files are gitignored.

Legend: ✅ supported, including features that require login; 🧩 supported only
when the upstream provider exposes the required method or result type; ➖ not
exposed in the app today.

| Feature | NetEase | QQ Music | Bilibili | YouTube Music |
| --- | --- | --- | --- | --- |
| Provider login/logout | ✅ | ✅ | ✅ | ✅ |
| Song search | ✅ | ✅ | ✅ | ✅ |
| Artist / album / playlist / video search tabs | 🧩 | 🧩 | 🧩 | 🧩 |
| Daily songs | ✅ | ✅ | ➖ | ✅ |
| Recommended playlists | ✅ | ✅ | ➖ | ✅ |
| Private FM / radio | ✅ | ✅ | ➖ | ➖ |
| Top lists | ✅ | ➖ | ➖ | ✅ |
| User playlists | ✅ | ✅ | ✅ | ✅ |
| Favorite songs | ✅ | ✅ | ➖ | ✅ |
| Favorite playlists | ✅ | ✅ | ✅ | ✅ |
| Favorite artists | ✅ | ✅ | ➖ | ✅ |
| Favorite albums | ✅ | ✅ | ➖ | ✅ |
| Followed Bilibili creators | ➖ | ➖ | ✅ | ➖ |
| Collected anime / films | ➖ | ➖ | ✅ | ➖ |
| Add song to user playlist | 🧩 | 🧩 | 🧩 | 🧩 |
| Remove song from playlist | 🧩 | 🧩 | 🧩 | ➖ |
| Similar songs / hot comments / song MV | 🧩 | 🧩 | 🧩 | 🧩 |
| Video playback | 🧩 | 🧩 | 🧩 | 🧩 |

Provider behavior can still vary with upstream service limits, region, login
state, and the exact FeelUOwn provider implementation.

## App Settings And Features

| Area | Current options |
| --- | --- |
| 🎛️ Providers | Enable or disable packaged providers, reorder provider priority, manage provider login, and switch login mode per provider. |
| 🎧 Audio quality | Separate Wi-Fi and cellular policies: highest, high, standard, or low-data. |
| 🔁 Unavailable tracks | Smart replacement or skip. Smart replacement can choose providers and minimum score while keeping the original track metadata and lyrics. |
| 🖼️ Playback display | Lyrics font size, system/light/dark mode, dynamic color, and preset color schemes. |
| 💽 Local music | Media permission entry, database-backed refresh, grouping by all/artist/album, directory inclusion, and minimum-duration filter. |
| ✏️ Local metadata | Edit title/artist/album, search provider metadata, and download lyrics into app-private storage. |
| 🧹 Cache | Configurable audio cache and image cache limits. |
| ⬇️ Downloads | Download provider tracks, play downloaded tracks locally, and remove downloaded files. |
| 🐞 Debug builds | Debug log viewer is available only in debug builds. |

## Project Structure

- `shared`: shared Compose UI, domain contracts, player state, common tests, and
  the Kotlin provider/network layer.
- `androidApp`: Android application, Media3 playback, assets, resources, and
  Android credential/cache stores.
- `shared/src/commonMain/kotlin/org/feeluown/mobile/provider`: Kotlin provider
  implementations, request policies, cache, retry, and domain mapping.
- `shared/src/commonMain/resources/audio_recognition`: bundled fingerprint runtime
  assets used by mobile audio recognition.
- `iosApp/FuoEvolve`: Swift app shell for experimental iOS builds.
- `.github/workflows`: Android APK and release workflows, plus the experimental
  iOS debug workflow.

## Requirements

- JDK 17 or newer.
- Android Studio or Android command-line tools for Android builds.
- Xcode on macOS for experimental iOS builds.

## Android Build

Build a debug Android APK with the checked-in Gradle wrapper:

```bash
./gradlew :androidApp:assembleDebug
```

Install it on a connected device or emulator:

```bash
./gradlew :androidApp:installDebug
```

The Android build includes the Kotlin provider implementations and their Ktor
network stack directly; it does not download or package a scripting runtime.

## iOS Status

The iOS project under `iosApp/FuoEvolve.xcodeproj` has experimental debug-build
support, including shared UI integration and audio recognition integration. Every
push to `master` builds a simulator debug artifact
in GitHub Actions. iOS is not released: do not treat its artifacts as
production-ready or expect a GitHub Release, App Store distribution, or end-user
installation support.

Provider and playback integration remain experimental.

## Testing

Run shared multiplatform tests:

```bash
./gradlew :shared:allTests
```

Run Android lint checks:

```bash
./gradlew :androidApp:lint :shared:lint
```

## Provider Extensions

To add a provider, implement the shared `KotlinMusicProvider` contract under
`shared/src/commonMain/kotlin/org/feeluown/mobile/provider`, register it in
`KotlinProviderRepository`, expose it from Settings, and add focused contract
tests. The default enabled provider set is NetEase:

```json
{
  "enabled": ["netease"]
}
```

## License

FuoEvolve is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE) for details.
