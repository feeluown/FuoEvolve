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

## Phase 6: playback UI migration

### C1: MiniPlayer

The MiniPlayer rendering/transport path consumes the dedicated playback session on both platforms.

- iOS has `IosPlaybackRuntime.kt`, mirroring the Android composition-edge adapter and constructing the same `DefaultPlaybackRuntime` contract.
- `FuoAppViewModel` receives the app-scoped `PlaybackSession`; `AppRoot` provides it to playback UI through `LocalPlaybackSession`.
- `RuntimeMiniPlayer` renders `PlaybackSessionState`, cover metadata from `TrackRef`, progress, lyrics, and previous/toggle/next transport without reading `FuoPlayerController`.
- Review feedback exposed an iOS pre-engine resource-resolution failure path. `IosPlaybackRuntime` now bridges only the same-track `Loading -> coordinator Error` transition until resource resolution becomes runtime-owned, with focused iOS regression tests.

### C2: FullPlayer, queue and lyrics

The active FullPlayer path is now controller-free.

- `RuntimeFullPlayer` consumes authoritative status, timing, lyrics, error, queue index, and previous/toggle/next transport from `PlaybackSession`.
- `PlaybackUiPort` carries only richer UI/application concerns that do not belong in the narrow runtime API: rich `MusicTrack` metadata, queue edits, seek/shuffle/repeat, sleep timer, audio/replacement information, downloads and now-playing actions.
- `ControllerPlaybackUiPort` is a temporary app-shell adapter over the remaining centralized owners. The playback UI itself has no `FuoPlayerController` dependency.
- Existing pure rendering helpers for cover transitions, progress, karaoke lyrics, controls and detail dialogs are reused through a render-only `PlaybackState` snapshot; session-owned fields remain sourced from `PlaybackSession`.
- `AppRoot` supplies both `LocalPlaybackSession` and `LocalPlaybackUiPort`, and the active full-player overlay renders `RuntimeFullPlayer`.
- Home and local-playlist MiniPlayer call sites now use the controller-free `PlaybackMiniPlayer` host. Other legacy feature screens can keep their old call signature until their own feature migration; that compatibility call does not change playback state/transport ownership.
- Architecture fitness rules scan `PlaybackUiPort`, playback composition contracts, `RuntimeMiniPlayer`, and `RuntimeFullPlayer` to prevent direct controller dependencies from returning.

## Next phases

1. Move the remaining `startCurrent` / `previous` / `next` queue-transition policy and resource-start orchestration out of `FuoPlayerController`, removing the final runtime queue bridge and the iOS pre-engine error compatibility bridge.
2. Replace `ControllerPlaybackUiPort` responsibilities with explicit playback/download/provider-detail owners as those feature boundaries become available.
3. Replace controller-backed Search/Recognition app-port operations as their sibling playback/download/provider-detail owners become explicit domain/feature ports.
4. Apply the feature-owned state plus app-shell composition pattern to local music, downloads, provider content, and settings; remove their legacy MiniPlayer call signatures during those migrations.
5. Continue moving stable contracts into compile-time modules and retire legacy aggregate provider dependencies and global loading/message state.
