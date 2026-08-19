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
- `:androidApp` consumes `:shared` plus playback/core APIs and adapts the platform engine and playback-owned coordinators into `:playback:runtime`.

These modules intentionally start small. New cross-feature/platform contracts should move into the appropriate API/runtime module instead of expanding the flat shared contract surface. Feature implementation modules can be split later after their ownership boundaries are stable.

## Shared source layout

The first migration stage groups existing source files physically while keeping the current `org.feeluown.mobile` Kotlin package. Keeping the package stable makes the move behavior-neutral and preserves binary/source references while the public controller facade is reduced in later changes.

- `app/`: app shell, navigation and app-scoped state.
- `core/model/`: legacy shared models during migration; stable new architecture models belong in `:core:model`.
- `core/ui/`: design system and cross-feature UI/platform abstractions.
- `feature/<name>/`: feature-local controller, state and UI.
- `feature/playback/`: playback composition, queue/start coordinators, and the remaining presentation compatibility adapters during migration.

Provider protocol implementations remain under `provider/<provider>` because they already have a useful adapter boundary.

## State ownership

Feature state should have one owner. New feature work should expose immutable UI state (preferably `StateFlow`) instead of adding new delegated properties to `FuoPlayerController`.

App-scoped navigation belongs to `AppNavigator` / `FuoAppViewModel`. Playback status, timing, current stable track reference, lyrics, queue identity/index, errors, and transport policy belong to `PlaybackSession` / `DefaultPlaybackRuntime`. Avoid introducing new app-global `isLoading`, `message`, or error flags; loading and errors should be feature-local.

Queue and start orchestration now have explicit playback owners. `PlaybackQueueCoordinator` owns `startCurrent` / `previous` / `next`, up-next priority, repeat/part transitions and queue-index selection while `PlaybackQueueController` remains the durable queue state holder. `PlaybackStartCoordinator` owns the prepare -> resolve/plan -> engine-start pipeline, including direct provider resolution on platforms that do not resolve resources inside the engine and `PlaybackPlan` construction for engines that do.

Pre-engine start failures are published through `PlaybackStartFailureSource`. Android and iOS runtime adapters combine that playback-owned failure with engine state, so the old iOS compatibility path that read coordinator/controller `Error` state has been retired.

MiniPlayer and FullPlayer read authoritative playback state/transport from `PlaybackSession`. FullPlayer-specific rich presentation and feature operations that do not belong in the narrow session contract—queue editing, seek/shuffle/repeat, sleep timer, rich `MusicTrack` metadata, downloads and replacement actions—flow through `PlaybackUiPort`. `ControllerPlaybackUiPort` is an app-shell compatibility adapter only; migrated playback UI does not depend on `FuoPlayerController` directly.

Legacy feature screens that still call the old `MiniPlayer(controller)` signature are outside the playback UI boundary. That signature remains only as a feature-shell compatibility call site while those screens are migrated; playback state and transport themselves remain session-owned.

## Repository dependencies

New features should depend on narrow provider capability interfaces (`ProviderSearchRepository`, `ProviderPlaybackRepository`, `ProviderAuthRepository`, and provider-neutral API contracts) rather than adding calls to the legacy aggregate `ProviderMusicRepository`.

## Composition roots

Platform dependency construction is isolated in platform containers. Android uses `AndroidAppContainer`; iOS uses `IosAppContainer`. `Application`, `UIViewController`, activities and services should remain thin hosts around those composition roots.

Search and Recognition are composed through explicit `SearchAppPort` / `RecognitionAppPort` contracts. Their routes and feature UI no longer accept `FuoPlayerController`. During the remaining migration, platform composition roots may adapt still-centralized controller operations to those ports; the dependency must not leak back into the feature or app route contract.

Android playback uses `AndroidPlaybackRuntime.kt` and iOS uses `IosPlaybackRuntime.kt` as composition-edge adapters. Both receive `PlaybackTransportCoordinator` and `PlaybackStartFailureSource` explicitly; they no longer dispatch runtime transport through controller methods or inspect controller error state. `FuoAppViewModel` exposes the resulting app-scoped `PlaybackSession`, and `AppRoot` supplies it to playback UI through `LocalPlaybackSession`. `AppRoot` also supplies the temporary `PlaybackUiPort` adapter separately so the stable runtime API does not expand into app-specific UI operations.

## Architecture fitness check

`checkArchitectureBoundaries` rejects new `FuoPlayerController` code dependencies inside migrated Search/Recognition boundaries, the entire `:playback:runtime` common source tree, `PlaybackQueueCoordinator`, `PlaybackStartCoordinator`, `PlaybackUiPort`, playback composition contracts, controller-free MiniPlayer/FullPlayer implementations, app-port contracts/routes, and Android playback service/Lyricon integration. It also rejects reintroduction of the retired Search/Recognition route shims and `ControllerPlaybackSession` adapter, plus direct `controller.toggle()/previous()/next()` calls in platform playback runtime adapters.

## Migration rule

Architecture migration should be incremental and behavior-preserving. Move ownership first, introduce narrow ports at cross-feature boundaries, and remove legacy facades only after callers have migrated and tests cover the new boundary.
