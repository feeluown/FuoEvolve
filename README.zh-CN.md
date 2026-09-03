# FuoEvolve

[![正式版](https://img.shields.io/github/v/release/feeluown/FuoEvolve?label=stable)](https://github.com/feeluown/FuoEvolve/releases/latest)
[![Master Canary](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml/badge.svg?branch=master)](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster)
[![PR Tests](https://github.com/feeluown/FuoEvolve/actions/workflows/pr-tests.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/pr-tests.yml)
[![Release](https://github.com/feeluown/FuoEvolve/actions/workflows/release.yml/badge.svg)](https://github.com/feeluown/FuoEvolve/actions/workflows/release.yml)
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
| 正式版 | [GitHub 最新 Release](https://github.com/feeluown/FuoEvolve/releases/latest) | 签名的 Android multi-ABI APK，包含 `arm64-v8a` 和 `x86_64`。 |
| F-Droid | [官方自托管仓库](https://feeluown.github.io/FuoEvolve/fdroid/repo?fingerprint=8D8BE45A04CF3242C13B43361C9FFA1CA8FB2F39D1A43CE35BEADFA8DBFEFB74) | 保留近期稳定版本，并在 GitHub Release 发布后自动更新。 |
| Canary | [master 最新 Android 构建](https://github.com/feeluown/FuoEvolve/actions/workflows/master-canary.yml?query=branch%3Amaster) | 从最新成功的 `master` workflow 下载签名 release APK。 |

## 项目亮点

- 🎵 **多音乐源整合**：在一个应用中使用网易云音乐、QQ 音乐、哔哩哔哩和 YouTube Music，并可自由启用、关闭和调整优先级。
- 🧭 **丰富的发现内容**：浏览每日推荐、私人 FM、排行榜、歌单广场、歌手广场、新歌新碟、MV、音乐风格以及各平台自己的特色内容。
- 🔎 **统一搜索**：同时搜索已启用的在线音乐源和本地音乐，在支持时按不同资源类型查看结果，并保留最近搜索历史以便快速再次搜索。
- ▶️ **更可靠的播放体验**：支持播放队列、随机/循环、稍后播放、进度跳转、多分 P、音频和视频播放、睡眠定时，以及 Android 进程重启后的队列与播放状态恢复。
- 🎤 **丰富且可补全的歌词体验**：在音乐源支持时显示同步歌词、翻译、罗马音和逐字歌词；对于没有可用歌词的来源，可以手动搜索并关联其他歌曲的歌词，并记住选择。
- 📱 **系统与设备歌词输出**：Android 可向支持的 ColorOS 锁屏、Lyricon 状态栏歌词以及支持的比亚迪仪表歌词能力发布实时歌词。
- 🔁 **更准确的智能换源**：当前资源不可用时，可自动在其他已启用音乐源中寻找更匹配的版本，并结合录音版本等信息提高匹配准确度，同时保留原歌曲上下文和歌词。
- ❤️ **管理在线媒体库**：在音乐源允许时，可收藏/取消收藏歌单、歌手和专辑，并直接管理网易云音乐和 QQ 音乐中的自建歌单。
- 💽 **本地音乐与离线播放**：可浏览和修改本地音乐、创建可导入导出的本地歌单、下载在线歌曲、断点续传未完成下载，并将已下载内容纳入本地媒体库管理。
- 🔐 **可迁移的登录凭证**：Android 支持导出加密的音乐源登录凭证备份，可整体或按单个音乐源导出并在之后恢复，同时不改变应用正常的本机安全存储方式。
- 🎙️ **听歌识曲**：通过麦克风识别正在播放的歌曲，并快速跳转到搜索或歌曲详情。
- 🔗 **分享直达**：Android 上可直接把网易云、QQ 音乐、哔哩哔哩、YouTube 或 YouTube Music 的分享链接发送给 FuoEvolve，自动打开对应内容；无法识别的分享文本也可以回退到应用内搜索。
- 🎨 **Material 3 Expressive 界面**：支持亮色/暗色、动态颜色、根据封面生成的播放器配色、流畅页面过渡，以及适配手机和更大屏幕的布局。

## 音乐源

FuoEvolve 目前内置四个在线音乐源。具体可用内容可能会受到平台能力、地区、登录状态和上游服务变化的影响。

| 音乐源 | 可以浏览的内容 |
| --- | --- |
| **网易云音乐** | 每日推荐歌曲、推荐新歌、私人 FM、排行榜、歌单广场、歌手广场、MV 广场、音乐风格、我喜欢、云盘歌曲、用户歌单、收藏歌手和专辑等，并支持可用的歌单和收藏写操作。 |
| **QQ 音乐** | 每日推荐、推荐内容、私人 FM、排行榜、歌单广场、歌手广场、新碟、MV 广场、多类型搜索、自建/收藏歌单、收藏专辑、关注歌手，以及可用的歌单和收藏写操作。 |
| **哔哩哔哩** | 音乐/视频搜索与播放、个性化推荐、动态视频、每周必看、稍后再看、观看历史、关注的 UP 主及投稿、收藏夹、追番和影视收藏，以及多分 P 播放。 |
| **YouTube Music** | 歌曲/视频搜索、推荐与媒体库内容、歌单、歌手、专辑、排行榜、歌词、相似歌曲电台，以及可用的视频播放能力。 |

所有音乐源都可以在设置中启用、关闭、排序和管理登录。网易云音乐、QQ 音乐和哔哩哔哩主要通过账号 Cookie 登录；YouTube Music 支持 Google 授权，也可以导入账号 Headers/Cookie。

## 播放与歌词

FuoEvolve 同时面向普通音乐播放和音乐/视频混合内容使用。可以管理播放队列，使用随机或循环模式，把歌曲加入“稍后播放”，拖动进度，从播放器直接进入歌手或专辑详情，并设置按时长或当前歌曲结束后停止播放的睡眠定时。

Android 上的播放会话和持久化队列会协同工作，因此在应用进程被系统回收并重新启动后，可以恢复当前歌曲、队列、随机/循环状态、多分 P 位置和播放进度。用户主动点击新歌曲或新歌单与“恢复上次播放”是分离的，不会因为旧暂停会话而覆盖新的播放选择。

MV 和视频拥有独立播放页面，支持播放控制、全屏显示、横竖屏切换，以及哔哩哔哩多分 P 内容。智能换源遇到多分 P 的替代结果时会把它作为单个替代资源处理，避免错误地把分 P 当成后续队列继续播放。

歌词支持普通时间轴歌词、翻译、罗马音，以及网易云和 QQ 音乐在可用时提供的富歌词/逐字时间信息。对于来源本身没有可用歌词的歌曲，可以手动搜索其他歌曲的歌词进行关联，并保存选择以供之后继续使用。哔哩哔哩会优先使用接口提供的 BGM 标题作为歌词搜索词，没有 BGM 信息时再回退到视频标题。

Android 还可以把实时歌词发布到播放器之外：支持的 ColorOS 设备可显示锁屏实时歌词，Lyricon 可提供状态栏歌词，支持对应能力的比亚迪车型可接收仪表歌词。

## 发现与个人媒体库

FuoEvolve 会尽量按照每个平台原本的内容特点来组织首页和“我的”，而不是把所有音乐源强行做成完全一致的结构。各音乐源内容支持增量加载，大型发现页和媒体库可以在继续加载后续内容时保持可操作。

网易云音乐和 QQ 音乐提供更完整的发现入口，例如排行榜、歌单广场、歌手浏览、MV 浏览，以及分类和筛选。网易云音乐额外支持音乐风格内容；QQ 音乐支持新碟浏览，并扩展了“我的”内容，包括收藏歌单、收藏专辑和关注歌手等。

在音乐源支持时，资源详情页会显示当前收藏状态，并允许直接收藏或取消收藏歌单、歌手和专辑。网易云和 QQ 音乐的自建歌单只暴露实际允许的操作；QQ 音乐支持创建和删除自建歌单，支持写操作的歌单可以添加或移除歌曲。

哔哩哔哩更贴近自身内容生态，可查看个性化推荐、动态视频、每周必看、稍后再看、观看历史、关注的 UP 主及投稿，以及收藏的番剧和影视内容。

“我的”还会根据播放记录展示常听歌单，让经常使用的内容更容易再次找到。搜索页会按最新优先保留最近搜索词，也支持删除单条历史记录。

## 本地音乐、歌单与下载

本地音乐可以扫描进入应用，并按全部歌曲、歌手、专辑或目录浏览。扫描时可以指定目录，也可以过滤时长过短的音频文件。本地索引会跟踪媒体变化和已保存歌词，因此大型媒体库刷新时不需要重复重建未变化的条目。

本地歌曲的标题、歌手和专辑信息可以直接修改，也可以借助已启用的在线音乐源查找更合适的元信息和歌词。手动关联的歌词也会在后续播放中继续生效。

本地歌单可以同时容纳本地歌曲、已下载歌曲以及受支持的在线歌曲。歌单可在应用内创建和管理，也可以从文件导入或分享为歌单文件。

支持的在线歌曲可以下载用于离线播放。下载状态和断点续传信息会被持久化，未完成的下载可以继续；完成后的资源会纳入离线/本地媒体库，按普通本地内容进行播放和浏览。

## 听歌识曲

在搜索页点击麦克风入口即可开始听歌识曲。应用只会录制生成音频指纹所需的声音，原始录音保留在内存中，并向识别服务发送音频指纹，而不是上传整段录音。

识别成功后可以直接搜索歌曲或打开网易云详情；在搜索页和详情页之间切换时，最近一次识别结果会保留，识别失败也可以直接重试。

## 个性化与设置

FuoEvolve 提供常用的个性化设置，同时尽量避免复杂配置。主要包括：

- 音乐源启用状态、优先级和登录管理；
- Android 上的加密登录凭证备份与恢复，并支持按单个音乐源导出；
- Wi-Fi 与蜂窝网络分别设置音质偏好；
- 资源不可用时的智能换源来源、行为和匹配严格度；
- 播放行为，例如其他应用开始播放音频时是否暂停 FuoEvolve；
- 独立外观设置页中的跟随系统、亮色或暗色主题、动态颜色、预设配色和封面衍生的播放器配色；
- 歌词字号，以及支持的 ColorOS、Lyricon、比亚迪等外部歌词输出；
- 本地音乐扫描目录和最短歌曲时长；
- 音频缓存和图片缓存上限。

## iOS 状态

iOS 目前仍处于实验阶段。项目已经包含 iOS 应用和持续集成构建，浏览、播放、MV 控制、听歌识曲等越来越多的共享功能也已经可以在 iOS 上运行。

但 iOS 暂未作为 GitHub Release 或 App Store 版本正式发布，因此目前还不属于面向普通用户支持的平台。

## 开发

FuoEvolve 使用 Kotlin Multiplatform 和 Compose Multiplatform 共享大部分应用代码，Android 端使用 AndroidX Media3 提供播放能力。当前代码已经拆分为明确的 feature、playback、provider、persistence 和平台边界，使共享 UI 不再直接承担音乐源实现或播放运行时职责。

环境要求：

- JDK 17 或更新版本；
- Android Studio 或 Android 命令行工具，用于 Android 构建；
- macOS + Xcode，用于 iOS 实验性构建。

构建并安装 Android Debug 版本：

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

运行共享测试和 Android lint：

```bash
./gradlew :shared:allTests
./gradlew :androidApp:lint :shared:lint
```

主要 Gradle 模块：

- `core:model`：共享应用/领域模型；
- `feature:*`：搜索、听歌识曲、本地/离线媒体库、音乐源浏览/登录/详情、设置、新手引导和首页等 feature owner；
- `playback:api` / `playback:runtime`：播放契约、会话编排、队列/歌词/定时逻辑和平台集成边界；
- `provider:api` / `provider:runtime` 以及 `provider:netease`、`provider:qqmusic`、`provider:bilibili`、`provider:ytmusic`：音乐源契约、运行时基础设施和各来源实现；
- `persistence:settings`：共享设置和用户选择的持久化；
- `shared`：共享 Compose 应用/UI 集成；
- `androidApp`：Android 应用和平台服务；
- `iosApp/FuoEvolve`：实验性的 iOS 应用；
- `.github/workflows`：PR 测试、master Canary 构建、正式发布，以及可复用的平台测试/打包流程。

## 许可证

FuoEvolve 使用 GNU General Public License v3.0 开源许可证，详情见 [LICENSE](LICENSE)。
