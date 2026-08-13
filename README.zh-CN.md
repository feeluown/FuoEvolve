# FuoEvolve

[![正式版](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/android-apk.yml?branch=master&label=canary)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![iOS CI](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/ios-debug-app.yml?branch=master&label=iOS%20CI)](https://github.com/feeluown/FuoEvolve/actions/workflows/ios-debug-app.yml?query=branch%3Amaster)
[![Android Release](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml)
[![测试覆盖率](https://codecov.io/gh/feeluown/FuoEvolve/branch/master/graph/badge.svg)](https://app.codecov.io/gh/feeluown/FuoEvolve)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Experimental-FF9500?logo=apple&logoColor=white)

中文 | [English](README.md)

FuoEvolve 是一个围绕 [FeelUOwn](https://github.com/feeluown/FeelUOwn) 生态构建的开源音乐播放器。它把多个在线音乐平台、本地音乐、歌单、下载、歌词和视频内容整合到同一个应用中，并提供现代化的 Material 3 使用体验。

目前 Android 是主要支持的平台；iOS 已有实验性构建，可用于体验和开发验证，但暂未面向普通用户正式发布。

## 下载

| 渠道 | 地址 | 说明 |
| --- | --- | --- |
| 正式版 | [GitHub 最新 Release](https://github.com/feeluown/FuoEvolve/releases/latest) | 签名的普通版 multi-ABI APK，以及按 `arm64-v8a` / `x86_64` 分包的智能版 APK。 |
| F-Droid | [官方自托管仓库](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | 保留近期稳定版本，并在 GitHub Release 发布后自动更新。 |
| Canary | [master 最新 Android 构建](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster) | 从最新成功的 `master` workflow 下载签名 release APK。 |

## 项目亮点

- 🎵 **多音乐源整合**：在一个应用中使用网易云音乐、QQ 音乐、哔哩哔哩和 YouTube Music，并可自由启用、关闭和调整优先级。
- 🧭 **丰富的发现内容**：浏览每日推荐、私人 FM、排行榜、歌单广场、歌手广场、新歌新碟、MV、音乐风格以及各平台自己的特色内容。
- 🔎 **统一搜索**：同时搜索已启用的在线音乐源和本地音乐，并在支持时按歌曲、歌手、专辑、歌单和视频分类查看结果。
- ▶️ **完整播放体验**：支持播放队列、随机/循环、稍后播放、进度跳转、多分 P、音频播放，以及带完整控制和全屏体验的 MV/视频播放。
- 🎤 **更完整的歌词体验**：支持同步歌词、翻译歌词、网易云逐字歌词的卡拉 OK 式高亮，以及部分 OPPO/OnePlus ColorOS 设备的锁屏实时歌词。
- 🔁 **智能换源**：当前歌曲资源不可用时，可自动在其他已启用音乐源中寻找更匹配的版本，同时尽量保留原歌曲信息和歌词。
- 💽 **本地音乐与本地歌单**：可按歌手、专辑或目录浏览本地音乐，修改元信息，创建本地歌单，并导入或分享歌单文件。
- ⬇️ **离线下载**：支持下载可用的在线歌曲，并在应用内直接进行本地播放。
- 🎙️ **听歌识曲**：通过麦克风识别正在播放的歌曲，并快速跳转到搜索或歌曲详情。
- 🔗 **分享直达**：Android 上可直接把网易云、QQ 音乐、哔哩哔哩、YouTube 或 YouTube Music 的分享链接发送给 FuoEvolve，自动打开对应内容；无法识别的分享文本也可以回退到应用内搜索。
- 🎨 **Material 3 Expressive 界面**：支持亮色/暗色、动态颜色、根据封面生成的播放器配色、流畅页面过渡，以及适配手机和更大屏幕的布局。

## 音乐源

FuoEvolve 目前内置四个在线音乐源。具体可用内容可能会受到平台能力、地区、登录状态和上游服务变化的影响。

| 音乐源 | 可以浏览的内容 |
| --- | --- |
| **网易云音乐** | 每日推荐歌曲、推荐新歌、私人 FM、排行榜、歌单广场、歌手广场、MV 广场、音乐风格、我喜欢、云盘歌曲、用户歌单、收藏歌手和专辑等。 |
| **QQ 音乐** | 每日推荐、推荐内容、私人 FM、排行榜、歌单广场、歌手广场、新碟、MV 广场、多类型搜索、我的歌单、收藏歌曲、歌手和专辑等。 |
| **哔哩哔哩** | 音乐/视频搜索与播放、个性化推荐、动态视频、每周必看、稍后再看、观看历史、关注的 UP 主及投稿、收藏夹、追番和影视收藏等。 |
| **YouTube Music** | 歌曲和视频搜索、推荐与媒体库内容、歌单、歌手、专辑、排行榜以及可用的视频播放能力。 |

所有音乐源都可以在设置中启用、关闭、排序和管理登录。网易云音乐、QQ 音乐和哔哩哔哩主要通过账号 Cookie 登录；YouTube Music 支持 Google 授权，也可以导入账号 Headers/Cookie。

## 播放与歌词

FuoEvolve 同时面向普通音乐播放和音乐/视频混合内容使用。可以管理播放队列，使用随机或循环模式，把歌曲加入“稍后播放”，拖动进度，并直接从播放器进入歌手或专辑详情。

MV 和视频拥有独立播放页面，支持播放控制、全屏显示、横竖屏切换，以及哔哩哔哩多分 P 内容。

歌词支持普通时间轴歌词、翻译歌词，以及网易云逐字歌词的跟随式高亮。在支持对应能力的 ColorOS 设备上，播放中的时间轴歌词还可以显示在锁屏界面。

## 发现与个人媒体库

FuoEvolve 会尽量按照每个平台原本的内容特点来组织首页和“我的”，而不是把所有音乐源强行做成完全一致的结构。

网易云音乐和 QQ 音乐提供更完整的发现入口，例如排行榜、歌单广场、歌手浏览、MV 浏览，以及分类和筛选。网易云音乐额外支持音乐风格内容，QQ 音乐则支持新碟浏览。

哔哩哔哩更贴近自身内容生态，可查看个性化推荐、动态视频、每周必看、稍后再看、观看历史、关注的 UP 主及投稿，以及收藏的番剧和影视内容。

“我的”还会根据播放记录展示常听歌单，让经常使用的内容更容易再次找到。

## 本地音乐、歌单与下载

本地音乐可以扫描进入应用，并按全部歌曲、歌手、专辑或目录浏览。扫描时可以指定目录，也可以过滤时长过短的音频文件。

本地歌曲的标题、歌手和专辑信息可以直接修改，也可以借助已启用的在线音乐源查找更合适的元信息和歌词。

本地歌单可以同时容纳本地歌曲、已下载歌曲以及受支持的在线歌曲。歌单可在应用内创建和管理，也可以从文件导入或分享为歌单文件。已经下载的歌曲可以继续在 FuoEvolve 中作为本地资源播放。

## 听歌识曲

在搜索页点击麦克风入口即可开始听歌识曲。应用只会录制生成音频指纹所需的声音，原始录音保留在内存中，并向识别服务发送音频指纹，而不是上传整段录音。

识别成功后可以直接搜索歌曲或打开网易云详情；在搜索页和详情页之间切换时，最近一次识别结果会保留，识别失败也可以直接重试。

## 个性化与设置

FuoEvolve 提供常用的个性化设置，同时尽量避免复杂配置。主要包括：

- 音乐源启用状态、优先级和登录管理；
- Wi-Fi 与蜂窝网络分别设置音质偏好；
- 资源不可用时的智能换源策略；
- 播放行为，例如其他应用开始播放音频时是否暂停 FuoEvolve；
- 跟随系统、亮色或暗色主题、动态颜色、预设配色和封面衍生的播放器配色；
- 歌词字号与显示偏好；
- 本地音乐扫描目录和最短歌曲时长；
- 音频缓存和图片缓存上限。

## iOS 状态

iOS 目前仍处于实验阶段。项目已经包含 iOS 应用和持续集成构建，浏览、播放、MV 控制、听歌识曲等越来越多的共享功能也已经可以在 iOS 上运行。

但 iOS 暂未作为 GitHub Release 或 App Store 版本正式发布，因此目前还不属于面向普通用户支持的平台。

## 开发

FuoEvolve 使用 Kotlin Multiplatform 和 Compose Multiplatform 共享大部分应用代码，Android 端使用 AndroidX Media3 提供播放能力。

环境要求：

- JDK 17 或更新版本；
- Android Studio 或 Android 命令行工具，用于 Android 构建；
- macOS + Xcode，用于 iOS 实验性构建。

构建并安装 Android Debug 版本：

```bash
./gradlew :androidApp:assembleStandardDebug
./gradlew :androidApp:assembleSmartDebug
./gradlew :androidApp:installStandardDebug
./gradlew :androidApp:installSmartDebug
```

运行共享测试和 Android lint：

```bash
./gradlew :shared:allTests
./gradlew :androidApp:lint :shared:lint
```

主要目录：

- `shared`：共享 UI、应用状态、音乐源实现和通用测试；
- `androidApp`：Android 应用和平台播放能力；
- `iosApp/FuoEvolve`：实验性的 iOS 应用；
- `.github/workflows`：Android 构建/发布流程和 iOS 开发构建。

## 许可证

FuoEvolve 使用 GNU General Public License v3.0 开源许可证，详情见 [LICENSE](LICENSE)。
