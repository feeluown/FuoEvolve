# FuoEvolve

[![正式版](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster)
[![Downloads](https://img.shields.io/github/downloads/feeluown/FuoEvolve/total?label=downloads)](https://github.com/feeluown/FuoEvolve/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)

中文 | [English](README.md)

FuoEvolve 是一个围绕 [FeelUOwn](https://github.com/feeluown/FeelUOwn) 生态构建的开源跨平台音乐播放器，将在线音乐、本地音乐、歌词、下载和视频整合到一个现代化应用中。

## 下载

| 平台 | 正式版 | Canary / 预览版 |
| --- | --- | --- |
| **Android** | [GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) · [F-Droid 仓库](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | [最新 master 构建](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) |
| **Windows x64** | 从 [GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) 下载 MSI | 从 [Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) 下载 MSI |
| **macOS arm64 / x64** | 从 [GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) 下载 DMG | 从 [Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) 下载 DMG |
| **Linux x64** | 从 [GitHub Release](https://github.com/feeluown/FuoEvolve/releases/latest) 下载 AppImage / Arch 包 | 从 [Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) 下载 AppImage / Arch 包 |
| **iOS** | — | 仅提供实验性开发构建 |

> 桌面端正式版使用 GraalVM Native Image，不再捆绑 JVM。Windows 与 macOS 安装包目前尚未完成生产签名 / 公证。

## 功能

- 🎵 **多音乐源**：在一个应用中使用网易云音乐、QQ 音乐、哔哩哔哩和 YouTube Music。
- 🧭 **发现与搜索**：浏览推荐、排行榜、歌单、歌手、专辑和视频，并统一搜索已启用的音乐源。
- ▶️ **音乐与视频播放**：支持播放队列、随机/循环、进度跳转、睡眠定时、MV / 视频和哔哩哔哩多分 P。
- 🎤 **丰富歌词**：支持同步歌词、翻译、罗马音、可用时的逐字歌词，以及手动匹配其他歌曲歌词。
- 🔁 **智能换源**：当前歌曲无法播放时，自动从其他已启用来源寻找合适的替代资源。
- 💽 **本地与离线音乐**：扫描本地音乐、编辑信息、创建本地歌单、下载在线歌曲并支持断点续传。
- ❤️ **音乐库管理**：在音乐源支持时管理收藏、歌单、专辑和歌手。
- 🎙️ **听歌识曲**：识别周围正在播放的音乐，并快速进入搜索或歌曲详情。
- 🔐 **可迁移登录凭证**：安全保存音乐源登录信息，并在需要时迁移受支持的登录凭证。
- 🎨 **Material 3 Expressive 界面**：自适应布局、亮色 / 暗色、动态颜色和封面跟随配色。

## 桌面端

桌面端与移动端共享 Compose 使用体验，支持 Windows、macOS 和 Linux。`desktopApp` 现在是唯一桌面宿主，使用 Nucleus/Tao + GraalVM Native Image 运行和发行，原 JVM 桌面应用已经移除。

- **Windows**：支持 SMTC 系统媒体控制，正式发行 MSI。
- **macOS**：支持 Now Playing / Remote Command Center，提供 Apple Silicon 与 Intel DMG。
- **Linux**：支持 MPRIS 与原生 Wayland/Tao，提供 AppImage 与 Arch 包；便携 AppImage 固定使用 Ubuntu 26.04 LTS 构建基线。
- **音视频播放**：通过 JNI 直接接入 libmpv，视频优先使用 GPU 渲染并提供软件回退。
- **托盘生命周期**：关闭窗口后继续播放和下载，可从托盘 / 状态栏图标恢复窗口或退出应用。
- **安全登录存储**：使用 Windows Credential Manager、macOS Keychain 和 Linux Secret Service / Libsecret。

正式版本 tag 会与 Android 一起发布完整桌面安装包，并附带 SHA-256 校验文件。桌面端暂不接入应用内自动更新，需要通过 GitHub Release 或 Canary 构建手动安装新版本。

## 音乐源

| 音乐源 | 主要内容 |
| --- | --- |
| **网易云音乐** | 推荐、私人 FM、排行榜、歌单、歌手、专辑、MV、收藏和云盘音乐 |
| **QQ 音乐** | 推荐、私人 FM、排行榜、歌单、歌手、专辑、MV、收藏和个人音乐库 |
| **哔哩哔哩** | 音乐 / 视频搜索、推荐、关注 UP 主、收藏、历史、稍后再看和多分 P 视频 |
| **YouTube Music** | 搜索、推荐、音乐库、歌单、歌手、专辑、电台、歌词和视频 |

具体可用内容会受到音乐源能力、地区、登录状态和上游服务变化影响。所有音乐源都可以在设置中启用、关闭、排序和管理登录。

## 平台状态

| 平台 | 状态 | 说明 |
| --- | --- | --- |
| Android | **稳定版** | 签名 APK 与 F-Droid 发行 |
| Windows | **稳定版** | x64 Native Image MSI；生产签名后续补充 |
| macOS | **稳定版** | Apple Silicon / Intel Native Image DMG；签名和公证后续补充 |
| Linux | **稳定版** | Native Image AppImage 与 Arch 包；AppImage 基线为 Ubuntu 26.04 LTS |
| iOS | **实验性** | 仅用于开发与 CI 验证 |

## 开发

FuoEvolve 使用 [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) 支持多个平台。

```bash
# 构建 Android Debug 包
./gradlew :androidApp:assembleDebug

# 运行 Native 桌面端
./gradlew :desktopApp:run
```

桌面端开发还需要 Rust/Cargo 工具链以及目标平台所需的原生依赖。具体运行与打包说明见 [desktopApp/README.md](desktopApp/README.md)、[docs/desktop-foundation.md](docs/desktop-foundation.md) 与 [docs/desktop-packaging.md](docs/desktop-packaging.md)。

## 参与贡献

欢迎提交问题、功能建议和 Pull Request。可前往 [Issues](https://github.com/feeluown/FuoEvolve/issues) 和 [Pull Requests](https://github.com/feeluown/FuoEvolve/pulls)。

## 许可证

FuoEvolve 使用 [GNU General Public License v3.0](LICENSE)。
