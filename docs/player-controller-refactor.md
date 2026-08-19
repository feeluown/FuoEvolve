# FuoPlayerController refactor

The controller migration is intentionally incremental. `FuoPlayerController` remains a compatibility facade while feature state and UI contracts move to narrower owners.

## Phase 1: search ownership

Search ownership has moved out of `FuoPlayerController`.

- `SearchFeatureScreen` consumes `SearchUiState`, provider/download snapshots, `SearchAction`, and narrow cross-feature callbacks instead of the global controller.
- `SearchFeatureController` is constructed by the Android/iOS composition roots. The same owner instance is injected into `FuoAppViewModel` and the compatibility facade, so production has one search state owner.
- The primary `SearchRoute` now accepts `SearchRouteDependencies` rather than a global controller. Existing app-shell callers are isolated in `SearchRouteCompat` until AppRoot is migrated.
- Search scope/provider preferences are restored into the feature owner and persisted through `AppSettingsRepository`.
- Search provider ordering/selection comes from `AppSettings`, while actual provider availability is gated by initialized provider sessions through a provider-neutral availability contract.

## Phase 2: recognition ownership

Recognition now follows the same ownership model.

- `RecognitionFeatureController` owns `StateFlow<RecognitionUiState>` and recognition operations through `RecognitionAction`.
- Android/iOS composition roots construct the recognition owner from the platform `AudioRecognitionRepository` and `PlaybackEngine`; pausing active playback no longer routes through the global controller.
- The primary `RecognitionRoute` composes `AudioRecognitionFeatureScreen` from feature state/actions plus narrow provider-detail/search callbacks. The old app-shell signature lives only in `RecognitionRouteCompat`.
- Permission changes and app-background cancellation enter through `FuoAppViewModel`.
- The compatibility facade keeps delegates to the same injected recognition owner, so production still has a single recognition state.
- Focused controller tests cover success/state ownership, playback pause, cancellation, and close/reset behavior.

## Phase 3: playback runtime boundary

The first playback runtime seam is now established.

- `:playback:api` defines `PlaybackSession` and immutable `PlaybackSessionState` as the platform/cross-feature playback contract.
- Android's media-session transport wiring, lock-screen lyric publication, durable queue/resume flushing, and Lyricon integration consume `PlaybackSession` instead of reading playback state directly from the global controller.
- `ControllerPlaybackSession` is intentionally a compatibility adapter: the existing controller still owns orchestration, while platform callers are decoupled from that ownership so a dedicated runtime can replace the adapter without another service integration rewrite.
- `:core:model` provides the stable `TrackRef` used by the playback API, and `:provider:api` starts the provider-neutral compile-time boundary used by Search.
- `checkArchitectureBoundaries` prevents migrated Search/Recognition and platform playback boundaries from regaining direct controller dependencies.

## Next phases

1. Replace `SearchRouteCompat` / `RecognitionRouteCompat` by migrating AppRoot to explicit app/domain action ports, then remove the remaining Search/Recognition compatibility delegates.
2. Move actual playback orchestration/state ownership from `ControllerPlaybackSession` into a dedicated runtime implementation; then migrate player UI to the same session contract.
3. Apply the feature-owned state plus app-shell composition pattern to local music, downloads, provider content, and settings.
4. Continue moving stable contracts into compile-time modules and retire legacy aggregate provider dependencies and global loading/message state.
