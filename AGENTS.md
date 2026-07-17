# Repository Guidelines

## Project Structure & Module Organization

FuoEvolve is a Kotlin Multiplatform mobile player prototype. The root Gradle project includes `:shared` and `:androidApp`; the Swift shell lives in `iosApp/`.

- `shared/src/commonMain/kotlin/org/feeluown/mobile`: shared Compose UI, contracts, and player state.
- `shared/src/androidMain` and `shared/src/iosMain`: platform-specific Kotlin implementations.
- `shared/src/commonTest`: multiplatform unit tests.
- `androidApp/src/main`: Android app manifest, resources, assets, Chaquopy packaging, and playback integration.
- `shared/src/commonMain/python/fuo_mobile`: Shared Python adapter around the FeelUOwn core.
- `iosApp/FuoEvolve`: Swift app shell and AVPlayer bridge.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper and JDK 17 or newer.

- `./gradlew :androidApp:assembleDebug`: build a debug Android APK with Chaquopy-packaged Python dependencies.
- `./gradlew :androidApp:installDebug`: install the debug app on a connected Android device or emulator.
- `./gradlew :shared:allTests`: run shared Kotlin Multiplatform tests across configured targets.
- `./gradlew :shared:iosSimulatorArm64Test`: run iOS simulator shared tests when Kotlin/Native tooling is available.
- `./gradlew :androidApp:lint :shared:lint`: run Android lint checks.
- `./gradlew clean`: remove generated build outputs.

## Coding Style & Naming Conventions

Write Kotlin with 4-space indentation, explicit package names under `org.feeluown.mobile`, and clear type names such as `FuoPlayerController`. Keep shared business logic in `commonMain`; add `*.android.kt` or `*.ios.kt` files only for platform-specific behavior. Swift files should use standard Swift naming (`PlayerViewModel`, `IOSNativeAudioEngine`). Python bridge code uses snake_case modules and functions.

## Testing Guidelines

Place shared tests in `shared/src/commonTest/kotlin` and name test files after the class under test, for example `FuoPlayerControllerTest.kt`. Prefer focused state and contract tests for shared logic before adding platform tests. Run `./gradlew :shared:allTests` before changing shared behavior, and run Android lint/build checks before Android integration changes.

## Commit & Pull Request Guidelines

Recent history uses Conventional Commit-style subjects, for example `feat: implement stage two music player` and `fix: stabilize mobile python bridge`. Keep subjects imperative and scoped to one change. Pull requests should describe user-visible behavior, list validation commands, link related issues when available, and include screenshots or recordings for UI/player changes.

## Security & Configuration Tips

Do not commit local SDK paths, signing keys, or machine-specific Python paths from `local.properties`. Provider configuration belongs in `androidApp/src/main/assets/providers.json`; update it together with any new Python dependency declared in `androidApp/build.gradle.kts`.
