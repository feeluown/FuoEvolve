# FuoEvolve

FeelUOwn mobile player prototype for Android and iOS.

The first stage uses Kotlin Multiplatform and Compose Multiplatform for shared UI
and state. FeelUOwn Python core plus provider plugins provide search and media
resolution. Android uses Media3 for native playback; iOS hosts the same shared
UI and fills platform services from `shared/src/iosMain`.

## Modules

- `shared`: shared Compose UI and player state.
- `androidApp`: Android app, Chaquopy bridge, Media3 playback service.
- `iosApp`: iOS Xcode host for the shared Compose UI.
- `androidApp/src/main/python/fuo_mobile`: Python adapter around FeelUOwn core.

## Android

The Android build packages Python through Chaquopy. Provider packages are
bundled in the APK, while the runtime enabled provider list is controlled from
Settings. The default enabled provider is NetEase only.

```bash
./gradlew :androidApp:assembleDebug
```

This project requires a modern Android toolchain and JDK 17 or newer.

## iOS

The iOS project is under `iosApp/FuoEvolve.xcodeproj`. Prepare Python resources
before building online provider support:

```bash
bash scripts/prepare-ios-python.sh
```

Then open the Xcode project; its build phase embeds the shared Kotlin framework.

The CI workflow `.github/workflows/ios-debug-app.yml` builds the iOS simulator
Debug app on macOS and uploads `FuoEvolve-debug-ios-simulator.zip` as an
artifact.

## Provider Extension

Add a Python package dependency, register the provider in the Android bridge,
and expose it from Settings. The fallback asset keeps NetEase as the only
default provider:

```json
{
  "enabled": ["netease"]
}
```
