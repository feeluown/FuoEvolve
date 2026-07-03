# FuoEvolve

[![Android APK](https://github.com/BruceZhang1993/FuoEvolve/actions/workflows/android-debug-apk.yml/badge.svg)](https://github.com/BruceZhang1993/FuoEvolve/actions/workflows/android-debug-apk.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-4285F4?logo=jetpackcompose&logoColor=white)
![Android](https://img.shields.io/badge/Android-Available-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-Planned-000000?logo=apple&logoColor=white)

[English](#english) | [中文](#中文)

## English

FuoEvolve is an open-source music player based on the
[FeelUOwn](https://github.com/feeluown/FeelUOwn) ecosystem. Android is currently
usable. iOS support is planned and not implemented yet.

The project uses Kotlin Multiplatform and Compose Multiplatform for shared UI
and player state. On Android, it packages the Python music-provider bridge with
Chaquopy and plays audio through AndroidX Media3.

### Highlights

- Shared Compose Multiplatform UI and player state foundation.
- FeelUOwn Python core integration for search, provider login, media resolution,
  lyrics, and cover metadata.
- Android playback through AndroidX Media3 and a Chaquopy-backed Python bridge.
- Usable Android app with provider configuration from assets and runtime
  Settings.
- iOS support is tracked as future work.
- GitHub Actions workflow for signed Android APK builds.

### Project Structure

- `shared`: shared Compose UI, domain contracts, player state, and common tests.
- `androidApp`: Android application, Chaquopy packaging, Media3 playback, assets,
  resources, and provider bridge wiring.
- `androidApp/src/main/python/fuo_mobile`: Python adapter around FeelUOwn core.
- `iosApp/FuoEvolve`: Swift app shell for future iOS support.
- `.github/workflows`: Android APK workflow and experimental iOS debug workflow.

### Requirements

- JDK 17 or newer.
- Android Studio or an Android command-line toolchain for Android builds.
- Xcode on macOS for future iOS work.
- Python 3.12 for Android Chaquopy packaging when a local build Python is needed.

### Android Build

Build a debug Android APK with the checked-in Gradle wrapper:

```bash
./gradlew :androidApp:assembleDebug
```

Install it on a connected device or emulator:

```bash
./gradlew :androidApp:installDebug
```

The Android build packages FeelUOwn and provider plugins through Chaquopy. The
default FeelUOwn source is the PyPI 5.1.2 sdist, and provider packages are
declared in `androidApp/build.gradle.kts`.

### iOS Status

The iOS project shell is under `iosApp/FuoEvolve.xcodeproj`, but iOS playback
and provider integration are not implemented yet. The helper script below is
reserved for future online provider work:

```bash
bash scripts/prepare-ios-python.sh
```

Do not treat the current iOS target as a usable player.

### Testing

Run shared multiplatform tests:

```bash
./gradlew :shared:allTests
```

Run Android lint checks:

```bash
./gradlew :androidApp:lint :shared:lint
```

### Provider Extensions

To add a provider, declare the Python dependency in `androidApp/build.gradle.kts`,
register it in the Android bridge, and expose it from Settings. The fallback
asset keeps NetEase as the default provider:

```json
{
  "enabled": ["netease"]
}
```

### License

FuoEvolve is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE) for details.

## 中文

FuoEvolve 是一个基于 [FeelUOwn](https://github.com/feeluown/FeelUOwn)
生态构建的开源音乐播放器。当前 Android 端可用，iOS 支持待实现。

项目使用 Kotlin Multiplatform 和 Compose Multiplatform 共享 UI 与播放器状态。
Android 端通过 Chaquopy 打包 Python 音乐源桥接层，并使用 AndroidX Media3 播放。

### 项目亮点

- 提供共享 Compose Multiplatform UI 与播放器状态基础。
- 集成 FeelUOwn Python Core，支持搜索、音乐源登录、媒体解析、歌词和封面元数据。
- Android 端使用 AndroidX Media3 播放，并通过 Chaquopy 连接 Python 桥接层。
- Android App 当前可用，并通过应用资产和运行时 Settings 管理音乐源配置。
- iOS 支持作为后续工作保留。
- GitHub Actions 提供 Android 签名 APK 构建。

### 项目结构

- `shared`：共享 Compose UI、领域契约、播放器状态和通用测试。
- `androidApp`：Android 应用、Chaquopy 打包、Media3 播放、资源和音乐源桥接。
- `androidApp/src/main/python/fuo_mobile`：围绕 FeelUOwn Core 的 Python 适配层。
- `iosApp/FuoEvolve`：为未来 iOS 支持保留的 Swift 应用外壳。
- `.github/workflows`：Android APK 工作流，以及实验性的 iOS Debug 工作流。

### 环境要求

- JDK 17 或更新版本。
- Android Studio 或 Android 命令行工具链。
- macOS + Xcode，用于后续 iOS 开发。
- Android Chaquopy 打包需要本地构建 Python 时，使用 Python 3.12。

### Android 构建

使用仓库内 Gradle Wrapper 构建 Debug APK：

```bash
./gradlew :androidApp:assembleDebug
```

安装到已连接的设备或模拟器：

```bash
./gradlew :androidApp:installDebug
```

Android 构建会通过 Chaquopy 打包 FeelUOwn 和音乐源插件。默认 FeelUOwn 来源为
PyPI 5.1.2 sdist，音乐源依赖声明在 `androidApp/build.gradle.kts`。

### iOS 状态

iOS 工程外壳位于 `iosApp/FuoEvolve.xcodeproj`，但 iOS 播放与音乐源集成尚未实现。
下面的辅助脚本为后续在线音乐源支持保留：

```bash
bash scripts/prepare-ios-python.sh
```

当前 iOS target 不应视为可用播放器。

### 测试

运行共享多平台测试：

```bash
./gradlew :shared:allTests
```

运行 Android lint：

```bash
./gradlew :androidApp:lint :shared:lint
```

### 音乐源扩展

添加音乐源时，需要在 `androidApp/build.gradle.kts` 中声明 Python 依赖，在 Android
桥接层注册该 provider，并从 Settings 中开放配置。默认兜底资产仅启用 NetEase：

```json
{
  "enabled": ["netease"]
}
```

### 许可证

FuoEvolve 使用 GNU General Public License v3.0 开源许可证。详情见
[LICENSE](LICENSE)。
