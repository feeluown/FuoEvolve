# FuoEvolve

[![Stable release](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/android-debug-apk.yml?branch=master&label=canary)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster)
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
state, and player contracts. On Android it packages the FeelUOwn Python core and
provider plugins with Chaquopy, then plays audio and video through AndroidX
Media3.

## Download

| Channel | Link | Packages |
| --- | --- | --- |
| Stable | [Latest GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) | Signed release APKs for `arm64-v8a`, `x86_64`, and universal devices. |
| F-Droid | [Official self-hosted repository](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | The five latest stable `arm64-v8a` releases, updated automatically after each GitHub Release. |
| Canary | [Latest master Android APK workflow](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster) | Artifacts from the newest successful master build: signed debug APK for development debugging, plus signed release APKs for `arm64-v8a`, `x86_64`, and universal devices. |

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

Provider packages currently bundled in the Android app:

| Provider | Package | Default | Login modes |
| --- | --- | --- | --- |
| NetEase Cloud Music | `fuo_netease==1.0.8` | Enabled | WebView, Cookie |
| QQ Music | `fuo-qqmusic==1.0.16` | Available in Settings | WebView, Cookie |
| Bilibili | `feeluown-bilibili==0.5.5` | Available in Settings | WebView, Cookie |
| YouTube Music | `fuo-ytmusic==0.4.18` | Available in Settings | WebView, Headers |

The app loads NetEase by default. QQ Music, Bilibili, and YouTube Music are
packaged and can be enabled, disabled, or reordered from Settings.

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
| 🔁 Unavailable tracks | Smart replacement or skip. Smart replacement can choose providers, minimum score, replacement metadata, and replacement lyrics. |
| 🖼️ Playback display | Lyrics font size, system/light/dark mode, dynamic color, and preset color schemes. |
| 💽 Local music | Media permission entry, database-backed refresh, grouping by all/artist/album, directory inclusion, and minimum-duration filter. |
| ✏️ Local metadata | Edit title/artist/album, search provider metadata, and download lyrics into app-private storage. |
| 🧹 Cache | Configurable audio cache and image cache limits. |
| ⬇️ Downloads | Download provider tracks, play downloaded tracks locally, and remove downloaded files. |
| 🐞 Debug builds | Debug log viewer is available only in debug builds. |

## Project Structure

- `shared`: shared Compose UI, domain contracts, player state, common tests, and
  the shared Python bridge.
- `androidApp`: Android application, Chaquopy packaging, Media3 playback,
  assets, resources, and provider bridge wiring.
- `shared/src/commonMain/python/fuo_mobile`: Python adapter around the FeelUOwn
  core and provider plugins.
- `shared/src/commonMain/resources/audio_recognition`: bundled fingerprint runtime
  assets used by mobile audio recognition.
- `iosApp/FuoEvolve`: Swift app shell for experimental iOS builds.
- `.github/workflows`: Android APK and release workflows, plus the experimental
  iOS debug workflow.

## Requirements

- JDK 17 or newer.
- Android Studio or Android command-line tools for Android builds.
- Python 3.12 when a local Chaquopy build Python is needed.
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

The Android build packages FeelUOwn and provider plugins through Chaquopy. The
default FeelUOwn source is the PyPI `5.1.2` sdist, and provider packages are
declared in `androidApp/build.gradle.kts`.

## iOS Status

The iOS project under `iosApp/FuoEvolve.xcodeproj` has experimental debug-build
support, including shared UI integration, Python runtime preparation, and audio
recognition integration. Every push to `master` builds a simulator debug artifact
in GitHub Actions. iOS is not released: do not treat its artifacts as
production-ready or expect a GitHub Release, App Store distribution, or end-user
installation support.

Prepare the Python runtime locally before building in Xcode:

```bash
bash scripts/prepare-ios-python.sh
```

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

To add a provider, declare the Python dependency in `androidApp/build.gradle.kts`,
add it to the Android provider registry, expose it from Settings, and wire any
provider-specific login or feature definitions in the bridge. The default enabled
provider set is NetEase:

```json
{
  "enabled": ["netease"]
}
```

## License

FuoEvolve is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE) for details.
