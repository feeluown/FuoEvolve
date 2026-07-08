# FuoEvolve

[![Stable release](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/badge/canary-1.0.1--3--geeb8ad2-orange)](https://github.com/feeluown/FuoEvolve/actions/runs/28952679677)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster)
[![Android Release](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Planned-000000?logo=apple&logoColor=white)

[中文](README.zh-CN.md) | English

FuoEvolve is an open-source music player based on the
[FeelUOwn](https://github.com/feeluown/FeelUOwn) ecosystem. Android is usable
today. iOS support is planned, but the current iOS target is still a shell.

The project uses Kotlin Multiplatform and Compose Multiplatform for shared UI,
state, and player contracts. On Android it packages the FeelUOwn Python core and
provider plugins with Chaquopy, then plays audio and video through AndroidX
Media3.

## Download

| Channel | Link | Packages |
| --- | --- | --- |
| Stable | [Latest GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) | Signed release APKs for `arm64-v8a`, `x86_64`, and universal devices. |
| Canary | [Latest master Android APK workflow](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster) | Artifacts from the newest successful master build: signed debug APK for development debugging, plus signed release APKs for `arm64-v8a`, `x86_64`, and universal devices. |

The latest stable release observed when this README was updated is
[`1.0.1`](https://github.com/feeluown/FuoEvolve/releases/tag/1.0.1). The latest
master canary build observed at that time is `1.0.1-3-geeb8ad2`.

## Highlights

- FeelUOwn-based online music provider integration on Android.
- Search across enabled providers and local music, with provider filtering.
- Typed provider search tabs for songs, artists, albums, playlists, and videos
  where the upstream provider supports those result types.
- Provider home sections for recommendations, exploration, user playlists, and
  favorites.
- Media3 audio playback, video/MV playback, queue management, shuffle, repeat,
  up-next, multi-part tracks, covers, and LRC lyrics.
- Smart replacement for unavailable tracks, with configurable provider pool,
  score threshold, metadata policy, and lyric policy.
- Downloads, app-private lyrics, local music database, local metadata edits, and
  provider-assisted metadata/lyric lookup.
- Direct system sharing with App Link-friendly share URLs.
- Runtime settings for providers, login, quality, playback behavior, local scan
  filters, cache limits, lyrics, and theme.

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

| Feature | NetEase | QQ Music | Bilibili | YouTube Music |
| --- | --- | --- | --- | --- |
| Provider login/logout | Yes | Yes | Yes | Yes |
| Song search | Yes | Yes | Login required for selected Bilibili search | Yes |
| Artist / album / playlist / video search tabs | Wired when returned by provider | Wired when returned by provider | Wired when returned by provider | Wired when returned by provider |
| Daily songs | Login required | Login required | No dedicated section | Public section |
| Recommended playlists | Login required | Login required | No dedicated section | Public section |
| Private FM / radio | Login required | Login required | No dedicated section | No dedicated section |
| Top lists | Public | No dedicated section | No dedicated section | Public |
| User playlists | Login required | Login required | Login required | Login required |
| Favorite songs | Login required | Login required | No dedicated section | Login required |
| Favorite playlists | Login required | Login required | Login required | Login required |
| Favorite artists | Login required | Login required | No dedicated section | Login required |
| Favorite albums | Login required | Login required | No dedicated section | Login required |
| Add song to user playlist | Supported when provider exposes playlist mutation | Supported when provider exposes playlist mutation | Supported when provider exposes playlist mutation | Supported when provider exposes playlist mutation |
| Remove song from playlist | Supported when provider exposes playlist mutation | Supported when provider exposes playlist mutation | Supported when provider exposes playlist mutation | Disabled in app for YouTube Music |
| Similar songs / hot comments / song MV | Wired through FeelUOwn provider methods when available | Wired through FeelUOwn provider methods when available | Wired through FeelUOwn provider methods when available | Wired through FeelUOwn provider methods when available |
| Video playback | Wired through provider video media methods when available | Wired through provider video media methods when available | Wired through provider video media methods when available | Wired through provider video media methods when available |

Provider behavior can still vary with upstream service limits, region, login
state, and the exact FeelUOwn provider implementation.

## App Settings And Features

| Area | Current options |
| --- | --- |
| Providers | Enable or disable packaged providers, reorder provider priority, manage provider login, and switch login mode per provider. |
| Audio quality | Separate Wi-Fi and cellular policies: highest, high, standard, or low-data. |
| Unavailable tracks | Smart replacement or skip. Smart replacement can choose providers, minimum score, replacement metadata, and replacement lyrics. |
| Playback display | Lyrics font size, system/light/dark mode, dynamic color, and preset color schemes. |
| Local music | Media permission entry, database-backed refresh, grouping by all/artist/album, directory inclusion, and minimum-duration filter. |
| Local metadata | Edit title/artist/album, search provider metadata, and download lyrics into app-private storage. |
| Cache | Configurable audio cache and image cache limits. |
| Downloads | Download provider tracks, play downloaded tracks locally, and remove downloaded files. |
| Debug builds | Debug log viewer is available only in debug builds. |

## Project Structure

- `shared`: shared Compose UI, domain contracts, player state, and common tests.
- `androidApp`: Android application, Chaquopy packaging, Media3 playback,
  assets, resources, and provider bridge wiring.
- `androidApp/src/main/python/fuo_mobile`: Python adapter around the FeelUOwn
  core and provider plugins.
- `iosApp/FuoEvolve`: Swift app shell for future iOS support.
- `.github/workflows`: Android APK and release workflows, plus the experimental
  iOS debug workflow.

## Requirements

- JDK 17 or newer.
- Android Studio or Android command-line tools for Android builds.
- Python 3.12 when a local Chaquopy build Python is needed.
- Xcode on macOS for future iOS work.

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

The iOS project shell is under `iosApp/FuoEvolve.xcodeproj`, but iOS playback
and provider integration are not implemented yet. The helper script below is
reserved for future online provider work:

```bash
bash scripts/prepare-ios-python.sh
```

Do not treat the current iOS target as a usable player.

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
