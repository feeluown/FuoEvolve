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
- The former Android-only `ControllerPlaybackSession` adapter is removed. Android now uses `AndroidPlaybackRuntime.kt` only to adapt the existing engine and the remaining legacy queue coordinator into the controller-free runtime module.
- Only `startCurrent`, `previous`, and `next` remain as temporary queue-transition callbacks to the legacy coordinator; queue selection and resource resolution are intentionally deferred so this slice stays behavior-preserving.
- Focused runtime tests cover state composition and transport dispatch, and CI runs `:playback:runtime:allTests` on both Android and iOS workflows.
- Architecture fitness rules scan the runtime module and reject reintroduction of `ControllerPlaybackSession`.

## Phase 6: playback UI migration C1

The MiniPlayer rendering/transport path now consumes the dedicated playback session on both platforms.

- iOS now has `IosPlaybackRuntime.kt`, mirroring the Android composition-edge adapter and constructing the same `DefaultPlaybackRuntime` contract.
- `FuoAppViewModel` receives the app-scoped `PlaybackSession`; `AppRoot` provides it to playback UI through `LocalPlaybackSession`.
- `RuntimeMiniPlayer` renders `PlaybackSessionState`, cover metadata from `TrackRef`, progress, lyrics, and previous/toggle/next transport without reading `FuoPlayerController`.
- The existing `MiniPlayer(controller)` entry point is intentionally kept as a C1-only presentation bridge for current screen call sites. It forwards only full-player visibility, transition direction, and the open-full-player action while all playback state/transport comes from `PlaybackSession`.
- `checkArchitectureBoundaries` scans the new MiniPlayer implementation and playback composition contract so controller dependencies cannot leak into the migrated path.
- FullPlayer, queue, lyrics presentation state, seek, shuffle/repeat, sleep timer, replacement actions, and removal of the temporary MiniPlayer entry bridge are deliberately deferred to C2 on the same PR after C1 review.

## Next phases

1. C2: migrate FullPlayer/queue/lyrics and playback-specific presentation actions to runtime-owned state/narrow playback UI ports, then remove the temporary `MiniPlayer(controller)` bridge.
2. Move the remaining `startCurrent` / `previous` / `next` queue-transition policy and resource-start orchestration out of `FuoPlayerController`, removing the final runtime queue bridge.
3. Replace controller-backed Search/Recognition app-port operations as their sibling playback/download/provider-detail owners become explicit domain/feature ports.
4. Apply the feature-owned state plus app-shell composition pattern to local music, downloads, provider content, and settings.
5. Continue moving stable contracts into compile-time modules and retire legacy aggregate provider dependencies and global loading/message state.
