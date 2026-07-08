# FuoEvolve

[![正式版](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Canary](https://img.shields.io/badge/canary-1.0.1--3--geeb8ad2-orange)](https://github.com/feeluown/FuoEvolve/actions/runs/28952679677)
[![Android APK](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster)
[![Android Release](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/android-release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Planned-000000?logo=apple&logoColor=white)

中文 | [English](README.md)

FuoEvolve 是一个基于 [FeelUOwn](https://github.com/feeluown/FeelUOwn)
生态构建的开源音乐播放器。当前 Android 端可用，iOS 端仍是工程外壳，尚未实现可用播放能力。

项目使用 Kotlin Multiplatform 和 Compose Multiplatform 共享 UI、状态和播放器契约。
Android 端通过 Chaquopy 打包 FeelUOwn Python Core 与音乐源插件，并使用 AndroidX Media3
播放音频和视频。

## 下载

| 渠道 | 地址 | 安装包 |
| --- | --- | --- |
| 正式版 | [GitHub 最新 Release](https://github.com/feeluown/FuoEvolve/releases/latest) | 签名 release APK，提供 `arm64-v8a`、`x86_64`、universal 三个包。 |
| Canary | [master 分支最新 Android APK workflow](https://github.com/feeluown/FuoEvolve/actions/workflows/android-debug-apk.yml?query=branch%3Amaster) | 在最新成功 workflow 的 Artifacts 中下载：用于开发者调试的签名 debug APK，以及 `arm64-v8a`、`x86_64`、universal 三个签名 release APK。 |

本 README 更新时可获取到的最新正式版是
[`1.0.1`](https://github.com/feeluown/FuoEvolve/releases/tag/1.0.1)，最新 master
canary 构建版本是 `1.0.1-3-geeb8ad2`。

## 项目亮点

- Android 端接入基于 FeelUOwn 的在线音乐源能力。
- 支持跨已启用音乐源和本地音乐搜索，并可按音乐源过滤。
- 在线搜索页按歌曲、歌手、专辑、歌单、视频分栏展示，具体结果类型取决于上游音乐源支持。
- 音乐源首页支持推荐、探索、我的歌单、收藏内容等分区。
- Media3 音频播放、视频/MV 播放、播放队列、稍后播放、随机、循环、多分 P、封面和 LRC 歌词。
- 资源不可用时支持智能替换，可配置替换音乐源、最低分、元信息策略和歌词策略。
- 支持下载、应用私有歌词、本地音乐数据库、本地元信息修改、在线匹配元信息和下载歌词。
- 支持直接调起系统分享，并生成适合 App Links 的分享 URL。
- 设置页支持音乐源、登录、音质、播放策略、本地扫描、缓存、歌词和主题等配置。

## 音乐源支持

Android App 当前已打包的音乐源插件：

| 音乐源 | 插件包 | 默认状态 | 登录方式 |
| --- | --- | --- | --- |
| 网易云音乐 | `fuo_netease==1.0.8` | 默认启用 | WebView、Cookie |
| QQ 音乐 | `fuo-qqmusic==1.0.16` | 设置中可启用 | WebView、Cookie |
| 哔哩哔哩 | `feeluown-bilibili==0.5.5` | 设置中可启用 | WebView、Cookie |
| YouTube Music | `fuo-ytmusic==0.4.18` | 设置中可启用 | WebView、Headers |

应用默认只加载网易云音乐。QQ 音乐、哔哩哔哩、YouTube Music 已随 Android 包打包，可在设置中启用、禁用和排序。

| 特性 | 网易云音乐 | QQ 音乐 | 哔哩哔哩 | YouTube Music |
| --- | --- | --- | --- | --- |
| 音乐源登录/退出 | 支持 | 支持 | 支持 | 支持 |
| 歌曲搜索 | 支持 | 支持 | 指定哔哩哔哩搜索需要登录 | 支持 |
| 歌手 / 专辑 / 歌单 / 视频搜索分栏 | 上游返回时展示 | 上游返回时展示 | 上游返回时展示 | 上游返回时展示 |
| 每日推荐歌曲 | 需登录 | 需登录 | 无独立入口 | 公开入口 |
| 推荐歌单 | 需登录 | 需登录 | 无独立入口 | 公开入口 |
| 私人 FM / 电台 | 需登录 | 需登录 | 无独立入口 | 无独立入口 |
| 排行榜 | 公开入口 | 无独立入口 | 无独立入口 | 公开入口 |
| 用户歌单 | 需登录 | 需登录 | 需登录 | 需登录 |
| 收藏歌曲 | 需登录 | 需登录 | 无独立入口 | 需登录 |
| 收藏歌单 | 需登录 | 需登录 | 需登录 | 需登录 |
| 收藏歌手 | 需登录 | 需登录 | 无独立入口 | 需登录 |
| 收藏专辑 | 需登录 | 需登录 | 无独立入口 | 需登录 |
| 添加歌曲到用户歌单 | 音乐源暴露接口时支持 | 音乐源暴露接口时支持 | 音乐源暴露接口时支持 | 音乐源暴露接口时支持 |
| 从歌单移除歌曲 | 音乐源暴露接口时支持 | 音乐源暴露接口时支持 | 音乐源暴露接口时支持 | 应用内禁用 |
| 相似歌曲 / 热门评论 / 歌曲 MV | 上游方法可用时展示 | 上游方法可用时展示 | 上游方法可用时展示 | 上游方法可用时展示 |
| 视频播放 | 上游视频媒体接口可用时播放 | 上游视频媒体接口可用时播放 | 上游视频媒体接口可用时播放 | 上游视频媒体接口可用时播放 |

音乐源实际表现仍可能受到上游服务限制、地区、登录状态和具体 FeelUOwn 插件实现影响。

## 应用设置与特性

| 区域 | 当前可设置项 |
| --- | --- |
| 音乐源 | 启用/禁用已打包音乐源、调整音乐源顺序、管理登录，并按音乐源切换登录方式。 |
| 音质 | Wi-Fi 和蜂窝网络分别配置：最高、高、标准、低流量。 |
| 不可用资源 | 可选智能替换或跳过；智能替换可配置替换音乐源、最低打分、是否使用替换元信息和替换歌词。 |
| 播放显示 | 歌词字号、跟随系统/亮色/暗色模式、动态颜色和预设配色方案。 |
| 本地音乐 | 媒体权限入口、数据库刷新、按全部/歌手/专辑分组、目录过滤和最短时长过滤。 |
| 本地元信息 | 修改标题/歌手/专辑，搜索在线元信息，并将歌词下载到应用私有存储。 |
| 缓存 | 可配置音频缓存和图片缓存上限。 |
| 下载 | 下载在线歌曲、本地播放已下载歌曲，并删除下载文件。 |
| Debug 构建 | 仅 debug 包显示日志查看入口。 |

## 项目结构

- `shared`：共享 Compose UI、领域契约、播放器状态和通用测试。
- `androidApp`：Android 应用、Chaquopy 打包、Media3 播放、资源和音乐源桥接。
- `androidApp/src/main/python/fuo_mobile`：围绕 FeelUOwn Core 与音乐源插件的 Python 适配层。
- `iosApp/FuoEvolve`：为未来 iOS 支持保留的 Swift 应用外壳。
- `.github/workflows`：Android APK 和 release 工作流，以及实验性的 iOS Debug 工作流。

## 环境要求

- JDK 17 或更新版本。
- Android Studio 或 Android 命令行工具链，用于 Android 构建。
- 本地 Chaquopy 构建需要 Python 时使用 Python 3.12。
- macOS + Xcode，用于后续 iOS 开发。

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

iOS 工程外壳位于 `iosApp/FuoEvolve.xcodeproj`，但 iOS 播放与音乐源集成尚未实现。
下面的辅助脚本为后续在线音乐源支持保留：

```bash
bash scripts/prepare-ios-python.sh
```

当前 iOS target 不应视为可用播放器。

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
