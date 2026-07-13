# FuoEvolve

[![正式版](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/github/actions/workflow/status/feeluown/FuoEvolve/android-debug-apk.yml?branch=master&label=canary)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster)
[![Android Release](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Experimental-FF9500?logo=apple&logoColor=white)

中文 | [English](README.md)

FuoEvolve 是一个基于 [FeelUOwn](https://github.com/feeluown/FeelUOwn)
生态构建的开源音乐播放器。当前 Android 端可用，已提供 iOS 实验性构建支持，但暂不发布 iOS 版本。

项目使用 Kotlin Multiplatform 和 Compose Multiplatform 共享 UI、状态和播放器契约。
Android 端通过 Chaquopy 打包 FeelUOwn Python Core 与音乐源插件，并使用 AndroidX Media3
播放音频和视频。

## 下载

| 渠道 | 地址 | 安装包 |
| --- | --- | --- |
| 正式版 | [GitHub 最新 Release](https://github.com/feeluown/FuoEvolve/releases/latest) | 签名 release APK，提供 `arm64-v8a`、`x86_64`、universal 三个包。 |
| Canary | [master 分支最新 Android APK workflow](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster) | 在最新成功 workflow 的 Artifacts 中下载：用于开发者调试的签名 debug APK，以及 `arm64-v8a`、`x86_64`、universal 三个签名 release APK。 |

iOS 仅提供实验性的 Debug 构建产物，不会作为 GitHub Release 发布，也不面向终端用户安装提供支持。

## 项目亮点

- 🎵 Android 端接入基于 FeelUOwn 的在线音乐源能力。
- 🔎 支持跨已启用音乐源和本地音乐搜索，并可按音乐源过滤。
- 🧭 在线搜索页按歌曲、歌手、专辑、歌单、视频分栏展示，具体结果类型取决于上游音乐源支持。
- 🏠 音乐源首页支持推荐、探索、我的歌单、收藏内容等分区。
- ▶️ Media3 音频播放、视频/MV 播放、播放队列、稍后播放、随机、循环、多分 P、封面和 LRC 歌词。
- 🔁 资源不可用时支持智能替换，可配置替换音乐源、最低分、元信息策略和歌词策略。
- ⬇️ 支持下载、应用私有歌词、本地音乐数据库、本地元信息修改、在线匹配元信息和下载歌词。
- 🔗 支持直接调起系统分享，并生成适合 App Links 的分享 URL。
- ⚙️ 设置页支持音乐源、登录、音质、播放策略、本地扫描、缓存、歌词和主题等配置。

## 音乐源支持

Android App 当前已打包的音乐源插件：

| 音乐源 | 插件包 | 默认状态 | 登录方式 |
| --- | --- | --- | --- |
| 网易云音乐 | `fuo_netease==1.0.8` | 默认启用 | WebView、Cookie |
| QQ 音乐 | `fuo-qqmusic==1.0.16` | 设置中可启用 | WebView、Cookie |
| 哔哩哔哩 | `feeluown-bilibili==0.5.5` | 设置中可启用 | WebView、Cookie |
| YouTube Music | `fuo-ytmusic==0.4.18` | 设置中可启用 | WebView、Headers |

应用默认只加载网易云音乐。QQ 音乐、哔哩哔哩、YouTube Music 已随 Android 包打包，可在设置中启用、禁用和排序。

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
| 🔁 不可用资源 | 可选智能替换或跳过；智能替换可配置替换音乐源、最低打分、是否使用替换元信息和替换歌词。 |
| 🖼️ 播放显示 | 歌词字号、跟随系统/亮色/暗色模式、动态颜色和预设配色方案。 |
| 💽 本地音乐 | 媒体权限入口、数据库刷新、按全部/歌手/专辑分组、目录过滤和最短时长过滤。 |
| ✏️ 本地元信息 | 修改标题/歌手/专辑，搜索在线元信息，并将歌词下载到应用私有存储。 |
| 🧹 缓存 | 可配置音频缓存和图片缓存上限。 |
| ⬇️ 下载 | 下载在线歌曲、本地播放已下载歌曲，并删除下载文件。 |
| 🐞 Debug 构建 | 仅 debug 包显示日志查看入口。 |

## 项目结构

- `shared`：共享 Compose UI、领域契约、播放器状态、通用测试和公共 Python bridge。
- `androidApp`：Android 应用、Chaquopy 打包、Media3 播放、资源和音乐源桥接。
- `shared/src/commonMain/python/fuo_mobile`：围绕 FeelUOwn Core 与音乐源插件的 Python 适配层。
- `iosApp/FuoEvolve`：用于 iOS 实验性构建的 Swift 应用外壳。
- `.github/workflows`：Android APK 和 release 工作流，以及实验性的 iOS Debug 工作流。

## 环境要求

- JDK 17 或更新版本。
- Android Studio 或 Android 命令行工具链，用于 Android 构建。
- 本地 Chaquopy 构建需要 Python 时使用 Python 3.12。
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

Android 构建会通过 Chaquopy 打包 FeelUOwn 和音乐源插件。默认 FeelUOwn 来源为
PyPI `5.1.2` sdist，音乐源依赖声明在 `androidApp/build.gradle.kts`。

## iOS 状态

`iosApp/FuoEvolve.xcodeproj` 已提供实验性 Debug 构建支持，包括共享 UI 集成和 Python
运行时准备。每次提交到 `master` 都会由 GitHub Actions 构建模拟器 Debug 产物。iOS 暂不
发布，不应将其视为生产可用版本；不会提供 GitHub Release、App Store 分发或面向终端用户的
安装支持。

在 Xcode 本地构建前，先准备 Python 运行时：

```bash
bash scripts/prepare-ios-python.sh
```

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

添加音乐源时，需要在 `androidApp/build.gradle.kts` 中声明 Python 依赖，加入 Android
音乐源注册表，从设置页开放配置，并在 bridge 中补齐该音乐源的登录或功能定义。默认启用的音乐源是网易云音乐：

```json
{
  "enabled": ["netease"]
}
```

## 许可证

FuoEvolve 使用 GNU General Public License v3.0 开源许可证。详情见
[LICENSE](LICENSE)。
