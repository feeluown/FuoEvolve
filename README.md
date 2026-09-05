# FuoEvolve

[![Stable release](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)

[中文](README.zh-CN.md) | English

FuoEvolve is an open-source, cross-platform music player built around the [FeelUOwn](https://github.com/feeluown/FeelUOwn) ecosystem. It brings online music services, local music, lyrics, downloads, and video into one modern app.

## Download

| Platform | Stable | Canary / Preview |
| --- | --- | --- |
| **Android** | [GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) · [F-Droid repository](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | [Latest master build](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) |
| **Windows x64** | — | MSI / EXE from [Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) |
| **macOS arm64 / x64** | — | DMG / PKG from [Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) |
| **Linux x64** | — | AppImage / DEB / RPM / Arch package from [Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) |
| **iOS** | — | Experimental development builds only |

> Desktop Canary packages are preview builds. Windows and macOS packages are not yet production-signed/notarized.

## Features

- 🎵 **Multiple music sources** — use NetEase Cloud Music, QQ Music, Bilibili, and YouTube Music in one app.
- 🧭 **Discover and search** — browse recommendations, charts, playlists, artists, albums, videos, and search across enabled sources.
- ▶️ **Music and video playback** — queue management, shuffle/repeat, seeking, sleep timer, MV/video playback, and multipart Bilibili content.
- 🎤 **Rich lyrics** — synchronized lyrics, translations, romanization, word-level timing where available, plus manual lyric matching.
- 🔁 **Smart source replacement** — automatically look for a suitable alternative when the current track cannot be played.
- 💽 **Local and offline music** — scan local files, edit metadata, create local playlists, download supported tracks, and resume interrupted downloads.
- ❤️ **Library management** — manage favorites, playlists, albums, and artists where the music service supports it.
- 🎙️ **Audio recognition** — identify nearby music and jump directly to search or song details.
- 🔐 **Portable account credentials** — securely store provider logins and move supported credentials between devices when needed.
- 🎨 **Material 3 Expressive UI** — adaptive layouts, light/dark themes, dynamic color, and cover-inspired player colors.

## Desktop

The desktop app uses the same shared interface and core experience as mobile instead of maintaining a reduced desktop-only UI.

- **Windows:** system media controls through SMTC.
- **macOS:** Now Playing / Remote Command Center integration.
- **Linux:** MPRIS media controls and native Wayland runtime support.
- **Tray lifecycle:** closing the window keeps playback and downloads running; the tray/status item can restore or exit the app.
- **Secure login storage:** Windows Credential Manager, macOS Keychain, and Linux Secret Service/Libsecret.

Desktop packages currently ship through the Canary workflow while release signing and final distribution are still being completed.

## Music Sources

| Source | Main content |
| --- | --- |
| **NetEase Cloud Music** | Recommendations, private FM, charts, playlists, artists, albums, MV, favorites, and cloud music |
| **QQ Music** | Recommendations, private FM, charts, playlists, artists, albums, MV, favorites, and library content |
| **Bilibili** | Music/video search, recommendations, followed creators, favorites, history, watch later, and multipart video |
| **YouTube Music** | Search, recommendations, library, playlists, artists, albums, radio, lyrics, and video |

Available content depends on the source, region, login state, and upstream service behavior. Sources can be enabled, disabled, reordered, and signed in from Settings.

## Platform Status

| Platform | Status | Notes |
| --- | --- | --- |
| Android | **Stable** | Primary release platform |
| Windows | **Canary** | Installable x64 desktop packages |
| macOS | **Canary** | Apple Silicon and Intel packages |
| Linux | **Canary** | Native Wayland target; AppImage and distribution packages |
| iOS | **Experimental** | Development and CI validation only |

## Development

FuoEvolve is built with Kotlin Multiplatform and Compose Multiplatform.

```bash
# Android debug build
./gradlew :androidApp:assembleDebug

# Run the desktop app
./gradlew :desktopApp:run
```

Desktop architecture and packaging details are documented in [docs/desktop-foundation.md](docs/desktop-foundation.md) and [docs/desktop-packaging.md](docs/desktop-packaging.md).

## Contributing

Bug reports, feature discussions, and pull requests are welcome. See [Issues](https://github.com/feeluown/FuoEvolve/issues) and [Pull Requests](https://github.com/feeluown/FuoEvolve/pulls).

## License

FuoEvolve is licensed under the [GNU General Public License v3.0](LICENSE).
