# FuoEvolve architecture boundaries

This document records the architecture boundaries used while the project migrates away from a flat `shared` source tree.

## Dependency direction

The intended dependency direction is:

`app -> feature -> core/api`

Platform hosts and adapters (`androidApp`, `iosMain`) implement or assemble dependencies required by shared code. Feature code must not become a platform service locator.

The first compile-time module boundaries are now explicit:

- `:core:model` owns stable cross-feature model contracts used by architecture APIs.
- `:playback:api` owns the app-scoped playback session contract and depends only on `:core:model` plus coroutines.
- `:provider:api` owns provider-neutral cross-feature capability contracts.
- `:shared` consumes those API modules and contains the current feature implementations and compatibility adapters.
- `:androidApp` consumes `:shared` plus the playback/core APIs required by platform integrations.

These modules intentionally start small. New cross-feature/platform contracts should move into the appropriate API module instead of expanding the flat shared contract surface. Feature implementation modules can be split later after their ownership boundaries are stable.

## Shared source layout

The first migration stage groups existing source files physically while keeping the current `org.feeluown.mobile` Kotlin package. Keeping the package stable makes the move behavior-neutral and preserves binary/source references while the public controller facade is reduced in later changes.

- `app/`: app shell, navigation and app-scoped state.
- `core/model/`: legacy shared models during migration; stable new architecture models belong in `:core:model`.
- `core/ui/`: design system and cross-feature UI/platform abstractions.
- `feature/<name>/`: feature-local controller, state and UI.
- `feature/playback/`: playback orchestration, the temporary session adapter and player UI.

Provider protocol implementations remain under `provider/<provider>` because they already have a useful adapter boundary.

## State ownership

Feature state should have one owner. New feature work should expose immutable UI state (preferably `StateFlow`) instead of adding new delegated properties to `FuoPlayerController`.

App-scoped navigation belongs to `AppNavigator` / `FuoAppViewModel`. Playback-specific overlays and session state belong to the playback feature. Avoid introducing new app-global `isLoading`, `message`, or error flags; loading and errors should be feature-local.

Platform playback integrations must consume `PlaybackSession`, not the global controller. `ControllerPlaybackSession` is a temporary compatibility adapter over the current controller-owned runtime; it establishes the replacement seam before runtime ownership is moved completely into a dedicated playback implementation.

## Repository dependencies

New features should depend on narrow provider capability interfaces (`ProviderSearchRepository`, `ProviderPlaybackRepository`, `ProviderAuthRepository`, and provider-neutral API contracts) rather than adding calls to the legacy aggregate `ProviderMusicRepository`.

## Composition roots

Platform dependency construction is isolated in platform containers. Android uses `AndroidAppContainer`; iOS uses `IosAppContainer`. `Application`, `UIViewController`, activities and services should remain thin hosts around those composition roots.

Search and Recognition are composed through explicit `SearchAppPort` / `RecognitionAppPort` contracts. Their routes and feature UI no longer accept `FuoPlayerController`. During the remaining migration, platform composition roots may adapt still-centralized controller operations to those ports; the dependency must not leak back into the feature or app route contract.

## Architecture fitness check

`checkArchitectureBoundaries` rejects new `FuoPlayerController` code dependencies inside migrated Search/Recognition feature boundaries, their app-port contracts/routes, and Android playback service/Lyricon integration. It also rejects reintroduction of the retired `SearchRouteCompat` / `RecognitionRouteCompat` app-shell shims.

## Migration rule

Architecture migration should be incremental and behavior-preserving. Move ownership first, introduce narrow ports at cross-feature boundaries, and remove legacy facades only after callers have migrated and tests cover the new boundary.
