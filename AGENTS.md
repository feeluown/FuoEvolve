# Repository Guidelines

## Project Structure & Module Organization

FuoEvolve is a Kotlin Multiplatform music player. The root Gradle project includes `:shared` and `:androidApp`; the Swift shell lives in `iosApp/`.

The project is migrating from a flat shared source tree toward feature-oriented boundaries. Keep the current Kotlin package `org.feeluown.mobile` stable while this migration is in progress; physical source location now communicates ownership even when package declarations have not changed yet.

### Shared common sources

`shared/src/commonMain/kotlin/org/feeluown/mobile` is organized by responsibility:

- `app/`: app shell, `AppRoot`, typed navigation routes, app settings repository, and app-scoped state.
- `core/model/`: cross-feature contracts and models shared by multiple features.
- `core/ui/`: design system, theme, shared UI components, and common UI/platform abstractions.
- `feature/playback/`: playback orchestration, queue, lyrics, sleep timer, player UI, and the compatibility `FuoPlayerController` facade.
- `feature/search/`: search controller, immutable search state, search history, and search UI.
- `feature/provider/`: provider-facing feature controllers, auth/session boundaries, provider content UI, and capability interfaces.
- `feature/settings/`: settings state/controller/UI and resource-cache settings.
- `feature/localmusic/`: local-library state, refresh policy, controller, and UI.
- `feature/localplaylist/`: local-playlist repository/controller/state/UI.
- `feature/download/`: download state/controller/policies/UI.
- `feature/recognition/`: audio-recognition contracts, controller, and UI.
- `feature/home/`, `feature/onboarding/`, `feature/debug/`: feature-specific UI/state for those areas.
- `provider/`: provider protocol implementations and network adapters for Bilibili, NetEase Cloud Music, QQ Music, YouTube Music, and provider-core infrastructure.

Platform source sets remain under `shared/src/androidMain` and `shared/src/iosMain`. Shared multiplatform tests live in `shared/src/commonTest`.

### Platform applications

- `androidApp/src/main`: Android process/app shell, services, Media3 playback integration, platform repositories/stores, permissions, intents, and Android-specific composition root.
- `androidApp/src/main/kotlin/org/feeluown/mobile/AndroidAppContainer.kt`: Android dependency composition and process-scoped runtime wiring. `FuoEvolveApplication` should remain a thin host.
- `shared/src/iosMain/.../IosAppHost.kt`: iOS Kotlin host and `IosAppContainer`; platform dependency construction should stay in the container rather than leaking into common feature code.
- `iosApp/FuoEvolve`: Swift application shell and native Apple-platform integration.

## Architecture Rules

The intended dependency direction is:

`platform app/container -> app shell -> feature -> core contracts`

Provider/platform implementations sit behind interfaces consumed by features. Do not use a platform host or global singleton as a service locator from shared feature code.

### Feature ownership and state

Each feature should own its state and operations. Prefer immutable `UiState` exposed through `StateFlow`/Flow and actions/events flowing back into the owning controller or view model.

Do not add new app-global `isLoading`, `message`, or generic error flags to `FuoPlayerController`. Loading, errors, transient feedback, filters, selections, and request state should normally be feature-local. App startup state and truly global overlays are exceptions and should have an explicit app-level owner.

Compose `mutableStateOf` is appropriate for UI-local presentation state. Long-lived business/application state should prefer observable state holders with explicit ownership rather than being added directly to the global controller facade.

### Navigation ownership

Typed routes are defined in the app layer. `AppNavigator` / `FuoAppViewModel` are the intended owners of application navigation.

Feature controllers may request navigation through explicit callbacks/events, but should not independently maintain a second navigation stack or duplicate route state. Avoid introducing parallel `selectedX`, `lastX`, and route-payload state for the same destination; prefer typed route payloads plus feature-owned loading/state restoration.

### Player/controller boundaries

`FuoPlayerController` currently exists as a compatibility facade and application coordinator, but it must not continue growing into the public API for every feature.

New search, provider, settings, library, playlist, download, recognition, or other feature behavior should be implemented in the corresponding feature controller/state holder. Playback-specific functionality belongs under `feature/playback`.

When touching existing controller code, prefer moving ownership toward narrower collaborators instead of adding another delegated property or unrelated method to `FuoPlayerController`.

### Repository and provider boundaries

Features should depend on narrow capability interfaces where available, such as `ProviderSearchRepository`, `ProviderPlaybackRepository`, and `ProviderAuthRepository`, rather than expanding direct use of the legacy aggregate `ProviderMusicRepository`.

Provider implementations should keep transport, parsing, credentials/session handling, cache behavior, and domain mapping separated. New provider capabilities should first define the smallest useful interface/port, then adapt existing aggregate repositories during migration.

### Models and contracts

Avoid adding unrelated types to `FuoContracts.kt`. New models should live near their bounded context (`playback`, `provider`, `download`, `library`, settings, etc.) unless they are truly shared cross-feature contracts.

Domain/contracts should not embed presentation concerns such as localized display labels when a UI/resource mapper can own them instead.

### Composition roots and platform adapters

Manual dependency injection is currently intentional; do not add Koin/Hilt solely to satisfy an architectural pattern.

Construct platform repositories, playback engines, stores, publishers, and process-scoped coordinators in `AndroidAppContainer` or `IosAppContainer`. Keep `Application`, activities, services, and view-controller hosts focused on lifecycle/platform responsibilities.

Common/domain code must not depend directly on Android or iOS APIs. Platform adapters implement common contracts and are wired by the platform container.

### Migration strategy

Favor incremental, behavior-preserving architecture changes:

1. establish physical feature/core/app boundaries;
2. move state and behavior to the correct owner;
3. keep compatibility adapters/facades while callers migrate;
4. remove legacy global APIs only after tests cover the new boundary;
5. introduce additional Gradle modules only after dependency boundaries are stable.

Do not create many Gradle modules while features still depend on `FuoPlayerController`; that would only turn the current monolith into a distributed monolith.

See `docs/architecture.md` and `docs/p0-architecture-migration.md` for the current migration direction.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper and JDK 17 or newer.

- `./gradlew :androidApp:assembleDebug`: build a debug Android APK.
- `./gradlew :androidApp:installDebug`: install the debug app on a connected Android device or emulator.
- `./gradlew :shared:allTests`: run configured shared Kotlin Multiplatform tests.
- `./gradlew :shared:iosSimulatorArm64Test`: run iOS simulator shared tests when Kotlin/Native tooling is available.
- `./gradlew :androidApp:lint :shared:lint`: run Android lint checks.
- `./gradlew clean`: remove generated build outputs.

For architecture refactors, run `:shared:allTests` first because the large existing controller tests serve as characterization/regression coverage while responsibilities are moved.

## Coding Style & Naming Conventions

Write Kotlin with 4-space indentation, explicit package names under `org.feeluown.mobile`, and clear responsibility-oriented type names. Keep shared business logic in `commonMain`; add `*.android.kt` or `*.ios.kt` files only for platform-specific behavior. Swift files should follow standard Swift naming.

Prefer small controllers/state holders with one bounded responsibility. UI composables should ideally consume state plus callbacks/actions rather than receiving the entire `FuoPlayerController` when introducing or substantially refactoring a feature.

## Testing Guidelines

Place shared tests in `shared/src/commonTest/kotlin` and name test files after the unit under test. Prefer focused state, policy, repository-port, navigation, and contract tests.

When extracting behavior from `FuoPlayerController`, preserve existing characterization coverage first, then move/add tests alongside the newly owning feature or collaborator. Run Android build/lint for Android integration changes and iOS/shared tests for changes affecting common or Apple-platform behavior.

## Commit & Pull Request Guidelines

Use Conventional Commit-style subjects such as `feat: ...`, `fix: ...`, `refactor: ...`, and `docs: ...`. Keep commits focused enough to review independently.

Architecture PRs should distinguish behavior-neutral moves from semantic changes. Large source reorganizations should keep package/API compatibility where practical so reviewers can separate renames from runtime behavior changes. PR descriptions should explain architecture impact, compatibility decisions, validation performed, and intentionally deferred follow-up work.

## Security & Configuration Tips

Do not commit local SDK paths, signing keys, credentials, tokens, or machine-specific paths from `local.properties`. Provider defaults belong in `androidApp/src/main/assets/providers.json`; provider credentials/session persistence must use the existing platform stores rather than hard-coded values.