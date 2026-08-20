# FuoPlayerController refactor

The controller migration is intentionally incremental. `FuoPlayerController` remains a compatibility facade while feature state and UI contracts move to narrower owners.

## Phase 1: search ownership

Search ownership has moved out of `FuoPlayerController`.

- `SearchFeatureScreen` consumes `SearchUiState`, provider/download snapshots, `SearchAction`, and narrow cross-feature callbacks instead of the global controller.
- `SearchFeatureController` is constructed by the Android/iOS composition roots. The same owner instance is injected into `FuoAppViewModel` and the compatibility facade, so production has one search state owner.
- The primary `SearchRoute` consumes `SearchAppPort`; it no longer accepts a global controller or a controller-backed route overload.
- Search scope/provider preferences are restored into the feature owner and persisted through `AppSettingsRepository`.
- Search provider ordering/selection comes from `AppSettings`, while actual provider availability is gated by initialized provider sessions through a provider-neutral availability contract.

## Phase 2: recognition ownership

Recognition now follows the same ownership model.

- `RecognitionFeatureController` owns `StateFlow<RecognitionUiState>` and recognition operations through `RecognitionAction`.
- Android/iOS composition roots construct the recognition owner from the platform `AudioRecognitionRepository` and `PlaybackEngine`; pausing active playback no longer routes through the global controller.
- The primary `RecognitionRoute` consumes `RecognitionAppPort`; the previous controller-backed app-shell overload has been removed.
- Permission changes and app-background cancellation enter through `FuoAppViewModel`.
- The compatibility facade keeps delegates to the same injected recognition owner where legacy callers still require them, so production still has a single recognition state.
- Focused controller tests cover success/state ownership, playback pause, cancellation, and close/reset behavior.

## Phase 3: playback runtime boundary

The first playback runtime seam is now established.

- `:playback:api` defines `PlaybackSession` and immutable `PlaybackSessionState` as the platform/cross-feature playback contract.
- Android's media-session transport wiring, lock-screen lyric publication, durable queue/resume flushing, and Lyricon integration consume `PlaybackSession` instead of reading playback state directly from the global controller.
- `:core:model` provides the stable `TrackRef` used by the playback API, and `:provider:api` starts the provider-neutral compile-time boundary used by Search.
- `checkArchitectureBoundaries` prevents migrated Search/Recognition and platform playback boundaries from regaining direct controller dependencies.

## Phase 4: app-shell compatibility removal

Search and Recognition no longer require controller-specific app-shell bridges.

- `SearchRouteCompat` and `RecognitionRouteCompat` are removed.
- `AppRoot` composes the primary routes directly from `FuoAppViewModel` plus `SearchAppPort` / `RecognitionAppPort`.
- Android and iOS composition roots implement those ports. While sibling playback/download/provider-detail responsibilities remain centralized, the controller dependency is confined to the composition edge instead of leaking into route or feature contracts.
- The architecture fitness check fails if either retired compat file is reintroduced.

## Phase 5: dedicated playback runtime

Playback platform state and transport policy now have a dedicated owner.

- New `:playback:runtime` contains `DefaultPlaybackRuntime`, which owns the authoritative `PlaybackSessionState` consumed by platform integrations.
- Runtime state combines engine timing/status/error with queue/current-track/lyrics presentation supplied at the composition edge.
- Play/pause/toggle decisions now live in the runtime and call the engine directly for pause/resume instead of round-tripping through `FuoPlayerController`.
- The former Android-only `ControllerPlaybackSession` adapter is removed. Android now uses `AndroidPlaybackRuntime.kt` only to adapt the existing engine and playback composition edge into the controller-free runtime module.
- `startCurrent`, `previous`, and `next` were initially left as temporary queue-transition callbacks while queue selection and resource resolution remained in the compatibility controller.
- Focused runtime tests cover state composition and transport dispatch, and CI runs `:playback:runtime:allTests` on both Android and iOS workflows.
- Architecture fitness rules scan the runtime module and reject reintroduction of `ControllerPlaybackSession`.

## Phase 6: playback UI migration

### C1: MiniPlayer

The MiniPlayer rendering/transport path consumes the dedicated playback session on both platforms.

- iOS has `IosPlaybackRuntime.kt`, mirroring the Android composition-edge adapter and constructing the same `DefaultPlaybackRuntime` contract.
- `FuoAppViewModel` receives the app-scoped `PlaybackSession`; `AppRoot` provides it to playback UI through `LocalPlaybackSession`.
- `RuntimeMiniPlayer` renders `PlaybackSessionState`, cover metadata from `TrackRef`, progress, lyrics, and previous/toggle/next transport without reading `FuoPlayerController`.
- Review feedback exposed an iOS pre-engine resource-resolution failure path. C1 temporarily bridged the same-track `Loading -> coordinator Error` transition until start orchestration received a playback-owned failure channel.

### C2: FullPlayer, queue and lyrics

The active FullPlayer path is controller-free.

- `RuntimeFullPlayer` consumes authoritative status, timing, lyrics, error, queue index, and previous/toggle/next transport from `PlaybackSession`.
- Existing pure rendering helpers for cover transitions, progress, karaoke lyrics, controls and detail dialogs are reused through a render-only `PlaybackState` snapshot; session-owned fields remain sourced from `PlaybackSession`.
- `AppRoot` supplies the app-scoped playback contracts and renders `RuntimeFullPlayer` as the active overlay.

## Phase 7: playback orchestration ownership (P1-D)

Queue transition and playback-start policy now have dedicated playback owners instead of living in `FuoPlayerController`.

- `PlaybackQueueCoordinator` owns `startCurrent`, `previous`, `next`, queue-index selection, up-next priority, repeat behavior, multi-part transitions, and dynamic-feature continuation decisions.
- `PlaybackQueueController` remains the durable queue state holder; the compatibility controller delegates queue transition entry points to the coordinator instead of implementing the policy itself.
- `PlaybackStartCoordinator` owns the prepare -> resolve/plan -> engine-start pipeline, including downloaded/local payload handling, provider resolution on iOS-style engines, `PlaybackPlan` look-ahead construction on Android-style internally resolving engines, multi-part payload mapping, stale-request protection, and start-failure publication.
- `PlaybackStartFailureSource` publishes pre-engine resolution failures. Android and iOS runtime adapters combine this playback-owned failure with engine state, replacing the temporary iOS bridge that inspected `FuoPlayerController.playbackState` for an `Error`.
- Android and iOS composition roots explicitly pass `PlaybackTransportCoordinator` and `PlaybackStartFailureSource` into their runtime adapters. Runtime transport no longer dispatches `controller.toggle()`, `controller.previous()`, or `controller.next()`.
- `FuoPlayerController` keeps facade methods for legacy feature callers, but those methods delegate to the playback owners. Request serial / part-selection storage remains facade-compatible in this phase so the migration does not mix state relocation with orchestration policy changes.
- Focused common tests cover up-next/repeat/part queue transitions, direct resource resolution success/failure, and internally resolving playback-plan construction. iOS tests verify same-track playback-start failures override Loading without unrelated failures leaking across tracks.
- Architecture checks prevent the new coordinators from depending on `FuoPlayerController` and reject direct controller transport calls in the platform runtime adapters.

## Phase 8: playback UI ownership (P1-E)

P1-E is completed as a sequence of ownership migrations rather than another controller adapter layer.

### E1: player-native UI owners

- `ControllerPlaybackUiPort` was removed.
- `PlaybackNavigationPort` owns FullPlayer / queue-overlay visibility.
- `PlaybackPresentationPort` reads rich track/part/audio presentation from `PlaybackEngine` plus lyric/theme presentation from `AppSettingsRepository`.
- `PlaybackQueueUiPort` is implemented directly by `PlaybackQueueCoordinator`.
- Durable queue metadata overlays engine presentation for the same track identity, including restored queue state and local metadata edits.

### E2: cross-feature now-playing ownership

The former `NowPlayingActionPort` aggregate is removed and replaced by explicit feature contracts:

- `DownloadActionPort` -> `DownloadController`.
- `PlaylistActionPort` -> `PlaylistActionController`.
- `ProviderTrackActionPort` -> `ProviderTrackActionController`.
- `LocalMusicActionPort` -> `LocalMusicController`.
- `ReplacementActionPort` -> `PlaybackReplacementController`.

`ControllerNowPlayingActionPort` no longer exists. Provider artist/album/original-detail and dislike behavior, playlist target/removal behavior, local metadata editing, download actions and manual replacement discovery/selection therefore have explicit owners. `FuoPlayerController` keeps only compatibility delegates where unmigrated sibling screens still call the old public API.

### E3: playback-end / sleep-timer lifecycle

- `PlaybackSleepTimerController` implements `PlaybackSleepTimerPort` directly.
- `PlaybackLifecycleCoordinator` owns the Playing/Ended state machine that decides whether an ended event completes an end-of-track timer or advances the queue/next playback part.
- The controller engine collector publishes the engine snapshot before executing the coordinator action, preserving the historical observer ordering.
- Multi-part behavior is preserved: an end-of-track timer only completes after the final playback part; intermediate part completion still advances playback.
- `ControllerPlaybackSleepTimerPort` is removed.

### E4: narrow player composition

- The business `PlaybackUiPort` aggregate is removed.
- `PlaybackUiGraph` remains only as a composition-time holder for the same owner instances; it has no business action contract.
- `RuntimeFullPlayer` installs the graph into narrow `CompositionLocal` ports and each sub-surface consumes only its required dependency.
- `PlaybackMiniPlayer` consumes the playback session plus navigation/queue state from the same graph.
- The old two-way Controller navigation mirror is removed; `DefaultPlaybackNavigationPort` is the single overlay owner.
- Android/iOS composition roots inject the actual playback/feature owners directly into `FuoAppViewModel` rather than constructing controller-backed playback UI adapters.
- Architecture fitness rules reject reintroduction of the compatibility ports, a `PlaybackUiPort` aggregate declaration, controller leakage into the migrated owners, and legacy `MiniPlayer(controller)` callers.

## Next phases

1. Replace the remaining controller-backed Search/Recognition app-port operations with the now-explicit Download / Provider Track / Playlist owners where appropriate.
2. Apply the feature-owned state plus app-shell composition pattern to local music, downloads, provider content, settings and other screens that still consume the broad controller facade.
3. Move remaining playback queue/start compatibility state (`request serial`, playback parts/index and facade-facing presentation) behind playback-owned state, then retire playback facade methods from `FuoPlayerController`.
4. Continue moving stable contracts into compile-time modules and retire legacy aggregate provider dependencies plus global loading/message/error state.
