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
- `feature/playback/`: playback composition, queue/start/lifecycle coordinators, playback UI owners and now-playing replacement behavior.

Provider protocol implementations remain under `provider/<provider>` because they already have a useful adapter boundary.

## State ownership

Feature state should have one owner. New feature work should expose immutable UI state (preferably `StateFlow`) instead of adding new delegated properties to `FuoPlayerController`.

App-scoped navigation belongs to `AppNavigator` / `FuoAppViewModel`. Playback status, timing, current stable track reference, lyrics, queue identity/index, errors, transport policy and playback-end lifecycle policy belong to playback-owned contracts/owners. Avoid introducing new app-global `isLoading`, `message`, or error flags; loading and errors should be feature-local.

Queue and start orchestration have explicit playback owners. `PlaybackQueueCoordinator` owns `startCurrent` / `previous` / `next`, up-next priority, repeat/part transitions, queue-index selection and controller-free source-queue selection for migrated cross-feature callers, while `PlaybackQueueController` remains the durable queue state holder. `PlaybackStartCoordinator` owns the prepare -> resolve/plan -> engine-start pipeline, including direct provider resolution on platforms that do not resolve resources inside the engine and `PlaybackPlan` construction for engines that do.

Pre-engine start failures are published through `PlaybackStartFailureSource`. Android and iOS runtime adapters combine that playback-owned failure with engine state, so the old iOS compatibility path that read coordinator/controller `Error` state has been retired.

`PlaybackLifecycleCoordinator` owns the Playing/Ended transition state machine that chooses between sleep-timer completion and queue auto-advance. `PlaybackSleepTimerController` owns sleep-timer state/commands and the end-of-track timer lifecycle contract. The controller engine collector publishes the current engine snapshot before executing the lifecycle action, preserving the historical state-observation ordering without owning the policy.

MiniPlayer and FullPlayer read authoritative playback state/transport from `PlaybackSession`. Rich player UI concerns are split into narrow contracts:

- `PlaybackNavigationPort` owns FullPlayer / queue-overlay visibility.
- `PlaybackPresentationPort` reads rich engine presentation plus lyric/theme settings and owns seek normalization.
- `PlaybackQueueUiPort` is implemented by `PlaybackQueueCoordinator` and owns queue display/edit, source-queue selection, shuffle/repeat and transition direction for player/cross-feature UI.
- `PlaybackSleepTimerPort` is implemented directly by `PlaybackSleepTimerController`.
- `DownloadActionPort` is implemented by `DownloadController`.
- `PlaylistActionPort` is implemented by `PlaylistActionController`.
- `ProviderTrackActionPort` is implemented by `ProviderTrackActionController`.
- `LocalMusicActionPort` is implemented by `LocalMusicController`.
- `ReplacementActionPort` is implemented by `PlaybackReplacementController`.

The broad `PlaybackUiPort`, `ControllerPlaybackUiPort`, `ControllerNowPlayingActionPort`, `ControllerPlaybackSleepTimerPort`, and two-way playback-navigation mirror are retired. `PlaybackUiGraph` is only a composition-time dependency holder; it does not own business state or actions. `RuntimeFullPlayer` installs that graph into narrow `CompositionLocal` contracts and every player sub-surface consumes only the port it needs.

`FuoPlayerController` may still expose compatibility facade methods to unmigrated sibling screens, but those methods delegate to the same feature/playback owner instances. New player UI must not route state or policy back through the facade.

## Repository dependencies

New features should depend on narrow provider capability interfaces (`ProviderSearchRepository`, `ProviderPlaybackRepository`, `ProviderAuthRepository`, and provider-neutral API contracts) rather than adding calls to the legacy aggregate `ProviderMusicRepository`.

## Composition roots

Platform dependency construction is isolated in platform containers. Android uses `AndroidAppContainer`; iOS uses `IosAppContainer`. `Application`, `UIViewController`, activities and services should remain thin hosts around those composition roots.

Search and Recognition are composed through explicit `SearchAppPort` / `RecognitionAppPort` contracts. Their routes and feature UI no longer accept `FuoPlayerController`. Android and iOS use the same shared app-port adapters, which compose those routes from provider-session state plus narrow playback/download/playlist/provider/navigation owners instead of rebuilding per-platform controller forwarding objects.

Android playback uses `AndroidPlaybackRuntime.kt` and iOS uses `IosPlaybackRuntime.kt` as composition-edge adapters. Both receive `PlaybackTransportCoordinator` and `PlaybackStartFailureSource` explicitly; they no longer dispatch runtime transport through controller methods or inspect controller error state. Android/iOS composition roots inject the playback navigation, presentation, queue, sleep-timer, download, playlist, provider-track, local-music and replacement owners explicitly into `FuoAppViewModel`. `AppRoot` only installs the resulting composition graph; it does not construct controller-backed playback adapters.

## Architecture fitness check

`checkArchitectureBoundaries` rejects new `FuoPlayerController` code dependencies inside migrated Search/Recognition boundaries, the entire `:playback:runtime` common source tree, Download/Local Music/Playlist/Provider Track now-playing owners, playback queue/start/lifecycle/replacement/sleep-timer owners, player composition contracts, controller-free MiniPlayer/FullPlayer implementations, app-port contracts/routes, and Android playback service/Lyricon integration.

It also rejects reintroduction of retired Search/Recognition route shims, `ControllerPlaybackSession`, `ControllerPlaybackUiPort`, and `ControllerPlaybackCompatibilityPorts`; rejects any new `PlaybackUiPort` aggregate declaration; rejects legacy `MiniPlayer(controller)` callers; rejects platform-local Search/Recognition app-port forwarding objects; and rejects direct `controller.toggle()/previous()/next()` calls in platform playback runtime adapters.

## Migration rule

Architecture migration should be incremental and behavior-preserving. Move ownership first, introduce narrow ports at cross-feature boundaries, and remove legacy facades only after callers have migrated and tests cover the new boundary.

The post-P1 migration order and exit criteria are tracked in [`p2-architecture-roadmap.md`](p2-architecture-roadmap.md).
