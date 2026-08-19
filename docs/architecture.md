# FuoEvolve architecture boundaries

This document records the architecture boundaries used while the project migrates away from a flat `shared` source tree.

## Dependency direction

The intended dependency direction is:

`app -> feature -> core/api`

Platform hosts and adapters (`androidApp`, `iosMain`) implement or assemble dependencies required by shared code. Feature code must not become a platform service locator.

The first compile-time module boundaries are now explicit:

- `:core:model` owns stable cross-feature model contracts used by architecture APIs.
- `:playback:api` owns the app-scoped playback session contract and depends only on `:core:model` plus coroutines.
- `:playback:runtime` owns the default `PlaybackSession` state/transport implementation and depends only on `:playback:api` plus coroutines.
- `:provider:api` owns provider-neutral cross-feature capability contracts.
- `:shared` consumes the playback/provider API contracts and contains the current feature implementations and legacy playback/provider contracts that are still being migrated.
- `:androidApp` consumes `:shared` plus playback/core APIs and adapts the existing platform engine/queue coordinator into `:playback:runtime`.

These modules intentionally start small. New cross-feature/platform contracts should move into the appropriate API/runtime module instead of expanding the flat shared contract surface. Feature implementation modules can be split later after their ownership boundaries are stable.

## Shared source layout

The first migration stage groups existing source files physically while keeping the current `org.feeluown.mobile` Kotlin package. Keeping the package stable makes the move behavior-neutral and preserves binary/source references while the public controller facade is reduced in later changes.

- `app/`: app shell, navigation and app-scoped state.
- `core/model/`: legacy shared models during migration; stable new architecture models belong in `:core:model`.
- `core/ui/`: design system and cross-feature UI/platform abstractions.
- `feature/<name>/`: feature-local controller, state and UI.
- `feature/playback/`: the remaining legacy playback coordinator and player UI while they migrate onto the runtime contract.

Provider protocol implementations remain under `provider/<provider>` because they already have a useful adapter boundary.

## State ownership

Feature state should have one owner. New feature work should expose immutable UI state (preferably `StateFlow`) instead of adding new delegated properties to `FuoPlayerController`.

App-scoped navigation belongs to `AppNavigator` / `FuoAppViewModel`. Playback-specific platform/session state now belongs to `DefaultPlaybackRuntime`. Avoid introducing new app-global `isLoading`, `message`, or error flags; loading and errors should be feature-local.

Platform playback integrations and migrated playback UI must consume `PlaybackSession`, not the global controller. `ControllerPlaybackSession` has been retired. `DefaultPlaybackRuntime` now owns the published session state and play/pause/toggle policy. Android and iOS both adapt their existing engine/queue coordinator into the same runtime contract. The composition edge still supplies the current queue/lyrics presentation and three temporary queue-transition callbacks (`startCurrent`, `previous`, `next`) while queue selection and resource-resolution policy are extracted from the legacy coordinator.

MiniPlayer is the first common playback UI consumer migrated to `PlaybackSessionState` and session transport controls. Its remaining full-player visibility/transition bridge is presentation-only and is scheduled for removal with the FullPlayer/queue/lyrics migration.

## Repository dependencies

New features should depend on narrow provider capability interfaces (`ProviderSearchRepository`, `ProviderPlaybackRepository`, `ProviderAuthRepository`, and provider-neutral API contracts) rather than adding calls to the legacy aggregate `ProviderMusicRepository`.

## Composition roots

Platform dependency construction is isolated in platform containers. Android uses `AndroidAppContainer`; iOS uses `IosAppContainer`. `Application`, `UIViewController`, activities and services should remain thin hosts around those composition roots.

Search and Recognition are composed through explicit `SearchAppPort` / `RecognitionAppPort` contracts. Their routes and feature UI no longer accept `FuoPlayerController`. During the remaining migration, platform composition roots may adapt still-centralized controller operations to those ports; the dependency must not leak back into the feature or app route contract.

Android playback uses `AndroidPlaybackRuntime.kt` and iOS uses `IosPlaybackRuntime.kt` as composition-edge adapters. The shared runtime module remains controller-free; only these adapters may bridge the remaining queue coordinator until that policy is moved into playback-owned components. `FuoAppViewModel` exposes the resulting app-scoped `PlaybackSession`, and `AppRoot` supplies it to playback UI through `LocalPlaybackSession`.

## Architecture fitness check

`checkArchitectureBoundaries` rejects new `FuoPlayerController` code dependencies inside migrated Search/Recognition boundaries, the entire `:playback:runtime` common source tree, the controller-free MiniPlayer implementation/composition contract, app-port contracts/routes, and Android playback service/Lyricon integration. It also rejects reintroduction of the retired Search/Recognition route shims and `ControllerPlaybackSession` adapter.

## Migration rule

Architecture migration should be incremental and behavior-preserving. Move ownership first, introduce narrow ports at cross-feature boundaries, and remove legacy facades only after callers have migrated and tests cover the new boundary.
