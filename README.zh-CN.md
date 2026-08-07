# FuoEvolve

[![正式版](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/android-apk.yml?branch=master&label=canary)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster)
[![iOS CI](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/ios-debug-app.yml?branch=master&label=iOS%20CI)](https://github.com/feeluown/FuoEvolve/actions/workflows/ios-debug-app.yml?query=branch%3Amaster)
[![Android Release](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Experimental-FF9500?logo=apple&logoColor=white)

中文 | [English](README.md)

FuoEvolve 是一个基于 [FeelUOwn](https://github.com/feeluown/FeelUOwn)
生态构建的开源音乐播放器。当前 Android 端可用，已提供 iOS 实验性构建支持，但暂不发布 iOS 版本。

项目使用 Kotlin Multiplatform 和 Compose Multiplatform 共享 UI、状态、播放器契约和音乐源实现。
音乐源层使用 Kotlin 与 Ktor 实现，Android 端使用 AndroidX Media3 播放音频和视频。

## 下载

| 渠道 | 地址 | 安装包 |
| --- | --- | --- |
| 正式版 | [GitHub 最新 Release](https://github.com/feeluown/FuoEvolve/releases/latest) | 一个包含 `arm64-v8a` 和 `x86_64` 的签名 multi-ABI release APK。 |
| F-Droid | [官方自托管仓库](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | 自动收录最近 5 个稳定版 multi-ABI 安装包，每次 GitHub Release 后自动更新。 |
| Canary | [master 分支最新 Android APK workflow](https://github.com/feeluown/FuoEvolve/actions/workflows/android-apk.yml?query=branch%3Amaster) | 在最新成功 workflow 的 Artifacts 中下载签名 debug APK 和签名 multi-ABI release APK。 |

iOS 仅提供实验性的 Debug 构建产物，不会作为 GitHub Release 发布，也不面向终端用户安装提供支持。

## 项目亮点

- 🎵 Android 端接入基于 FeelUOwn 的在线音乐源能力。
- 🔎 支持跨已启用音乐源和本地音乐搜索，并可按音乐源过滤。
- 🧭 在线搜索页按歌曲、歌手、专辑、歌单、视频分栏展示，具体结果类型取决于上游音乐源支持。
- 🏠 音乐源首页支持推荐、探索、我的歌单、收藏内容等分区。
- ▶️ Media3 音频播放、视频/MV 播放、播放队列、稍后播放、随机、循环、多分 P、封面、LRC 歌词，
  并支持从全屏播放器打开歌手和专辑详情。
- 🎙️ Android 和 iOS 实验性构建支持听歌识曲，展示识别结果，并可直接搜索或查看网易云详情。
- 📚 播放歌单时先播放已加载曲目，同时在后台增量加载剩余曲目，支持较大歌单的随机播放。
- 🔁 资源不可用时支持智能替换，可配置替换音乐源、最低分、元信息策略和歌词策略。
- ⬇️ 支持下载、应用私有歌词、本地音乐数据库、本地元信息修改、在线匹配元信息和下载歌词。
- 🔗 支持直接调起系统分享，并生成适合 App Links 的分享 URL。
- ⚙️ 设置页支持音乐源、登录、音质、播放策略、本地扫描、缓存、歌词和主题等配置。

## 听歌识曲

在搜索页点击麦克风入口即可打开听歌识曲。Android 和 iOS 实验性构建会请求麦克风权限，
录音仅在内存中用于生成音频指纹，识别接口只会收到音频指纹，不会保存或上传原始音频。
识别成功后会展示歌曲信息，并支持直接搜索或打开网易云详情；从搜索或详情页返回时，识别结果仍会保留。
识别失败时会结束本次识别并提供重试入口。

## 音乐源支持

当前共享 Kotlin 模块内置的音乐源实现：

| 音乐源 | Kotlin 实现 | 默认状态 | 登录方式 |
| --- | --- | --- | --- |
| 网易云音乐 | `NeteaseProvider` | 默认启用 | Cookie |
| QQ 音乐 | `QQMusicProvider` | 设置中可启用 | Cookie |
| 哔哩哔哩 | `BilibiliProvider` | 设置中可启用 | Cookie |
| YouTube Music | `YtMusicProvider` | 设置中可启用 | OAuth（TV）/ Headers |

应用默认只加载网易云音乐。QQ 音乐、哔哩哔哩、YouTube Music 已随 Android 包打包，可在设置中启用、禁用和排序。

YouTube Music 支持两种登录方式：

1. **Google OAuth（TV / Limited Input device-code）**：与 [ytmusicapi OAuth](https://ytmusicapi.readthedocs.io/en/stable/setup/oauth.html) 相同。在 Google Cloud 创建「TVs and Limited Input devices」类型的 OAuth 客户端并启用 YouTube Data API，在设置里填写或导入 Console 下载的 `client_secret_*.json`，然后点击「使用 Google 登录（TV）」按验证码完成授权。也可导入桌面端 `ytmusicapi oauth` 生成的 `oauth.json`（需先有同一对 client 凭证以便刷新）。
2. **Headers / Cookie**：导入 `ytmusic_header.json` 或手填 Authorization + Cookie。

本地 `oauth.json` 与 `client_secret*.json` 不会被提交到仓库。

图例：✅ 支持，包括需要登录后使用的能力；🧩 依赖上游音乐源暴露对应方法或返回对应结果类型；➖ 当前应用没有入口或未开放。

| 特性 | 网易云音乐 | QQ 音乐 | 哔哩哔哩 | YouTube Music |
| --- | --- | --- | --- | --- |
| 音乐源登录/退出 | ✅ | ✅ | ✅ | ✅ |
| 歌曲搜索 | ✅ | ✅ | ✅ | ✅ |
| 歌手 / 专辑 / 歌单 / 视频搜索分栏 | 🧩 | 🧩 | 🧩 | 🧩 |
| 每日推荐歌曲 | ✅ | ✅ | ➖ | ✅ |
| 推荐歌单 | ✅ | ✅ | ➖ | ✅ |
| 私人 FM / 电台 | ✅ | ✅ | ➖ | ➖ |
| 排行榜 | ✅ | ➖ | ➖ | ✅ |
| 用户歌单 | ✅ | ✅ | ✅ | ✅ |
| 收藏歌曲 | ✅ | ✅ | ➖ | ✅ |
| 收藏歌单 | ✅ | ✅ | ✅ | ✅ |
| 收藏歌手 | ✅ | ✅ | ➖ | ✅ |
| 收藏专辑 | ✅ | ✅ | ➖ | ✅ |
| 关注的 Bilibili UP 主 | ➖ | ➖ | ✅ | ➖ |
| 收藏番剧 / 影视 | ➖ | ➖ | ✅ | ➖ |
| 添加歌曲到用户歌单 | 🧩 | 🧩 | 🧩 | 🧩 |
| 从歌单移除歌曲 | 🧩 | 🧩 | 🧩 | ➖ |
| 相似歌曲 / 热门评论 / 歌曲 MV | 🧩 | 🧩 | 🧩 | 🧩 |
| 视频播放 | 🧩 | 🧩 | 🧩 | 🧩 |

音乐源实际表现仍可能受到上游服务限制、地区、登录状态和具体 FeelUOwn 插件实现影响。

## 应用设置与特性

| 区域 | 当前可设置项 |
| --- | --- |
| 🎛️ 音乐源 | 启用/禁用已打包音乐源、调整音乐源顺序、管理登录，并按音乐源切换登录方式。 |
| 🎧 音质 | Wi-Fi 和蜂窝网络分别配置：最高、高、标准、低流量。 |
| 🔁 不可用资源 | 可选智能替换或跳过；智能替换可配置替换音乐源和最低打分，并固定使用原歌曲信息与歌词。 |
| 🖼️ 播放显示 | 歌词字号、跟随系统/亮色/暗色模式、动态颜色和预设配色方案。 |
| 💽 本地音乐 | 媒体权限入口、数据库刷新、按全部/歌手/专辑分组、目录过滤和最短时长过滤。 |
| ✏️ 本地元信息 | 修改标题/歌手/专辑，搜索在线元信息，并将歌词下载到应用私有存储。 |
| 🧹 缓存 | 可配置音频缓存和图片缓存上限。 |
| ⬇️ 下载 | 下载在线歌曲、本地播放已下载歌曲，并删除下载文件。 |
| 🐞 Debug 构建 | 仅 debug 包显示日志查看入口。 |

## 项目结构

- `shared`：共享 Compose UI、领域契约、播放器状态、通用测试和 Kotlin 音乐源/网络层。
- `androidApp`：Android 应用、Media3 播放、资源以及 Android 凭据/缓存存储。
- `shared/src/commonMain/kotlin/org/feeluown/mobile/provider`：Kotlin 音乐源实现、请求策略、缓存、重试和领域映射。
- `shared/src/commonMain/resources/audio_recognition`：移动端听歌识曲使用的音频指纹运行时资源。
- `iosApp/FuoEvolve`：用于 iOS 实验性构建的 Swift 应用外壳。
- `.github/workflows`：Android APK 和 release 工作流，以及实验性的 iOS Debug 工作流。

## 环境要求

- JDK 17 或更新版本。
- Android Studio 或 Android 命令行工具链，用于 Android 构建。
- macOS + Xcode，用于 iOS 实验性构建。

## Android 构建

使用仓库内 Gradle Wrapper 构建 Debug APK：

```bash
./gradlew :androidApp:assembleDebug
```

安装到已连接的设备或模拟器：

```bash
./gradlew :androidApp:installDebug
```

Android 构建会直接包含 Kotlin 音乐源实现和 Ktor 网络层，不下载或打包脚本运行时。

## iOS 状态

`iosApp/FuoEvolve.xcodeproj` 已提供实验性 Debug 构建支持，包括共享 UI 集成和听歌识曲集成。
每次提交到 `master` 都会由 GitHub Actions 构建模拟器 Debug
产物。iOS 暂不发布，不应将其视为生产可用版本；不会提供 GitHub Release、App Store 分发
或面向终端用户的安装支持。

音乐源和播放集成仍处于实验阶段。

## 测试

运行共享多平台测试：

```bash
./gradlew :shared:allTests
```

运行 Android lint：

```bash
./gradlew :androidApp:lint :shared:lint
```

## 音乐源扩展

添加音乐源时，在 `shared/src/commonMain/kotlin/org/feeluown/mobile/provider` 下实现
`KotlinMusicProvider` 契约，在 `KotlinProviderRepository` 注册，并补充契约测试和设置页入口。
默认启用的音乐源是网易云音乐：

```json
{
  "enabled": ["netease"]
}
```

## 许可证

FuoEvolve 使用 GNU General Public License v3.0 开源许可证。详情见
[LICENSE](LICENSE)。
