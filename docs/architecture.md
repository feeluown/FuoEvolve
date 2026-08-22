# FuoEvolve architecture boundaries

This document records the compile-time and ownership boundaries after the P2 ownership migration and the current P3 physical feature extraction.

## Dependency direction

The intended dependency direction is:

`platform composition root -> app shell -> feature owner -> core/api`

Platform hosts (`androidApp`, iOS host/adapters) construct platform dependencies and feature owners. Feature implementations must not use a platform service locator or depend back on `:shared`.

The current compile-time modules are:

- `:core:model`: stable cross-feature model contracts.
- `:provider:api`: provider-neutral capability contracts.
- `:playback:api`: app-scoped playback session contracts.
- `:playback:runtime`: controller-free playback session state/transport implementation.
- `:feature:recognition`: physical Recognition feature module containing recognition contracts, state, controller and tests.
- `:feature:search`: physical Search feature module containing search actions, state ownership, repository/result ports, orchestration and tests.
- `:feature:localplaylist`: physical Local Playlist state/orchestration boundary.
- `:feature:localmusic`: physical Local Music state/orchestration boundary.
- `:feature:download`: physical Download state/orchestration and offline-library coordination boundary.
- `:shared`: app shell, shared Compose/design primitives and application bindings for physical feature modules plus feature implementations not yet physically extracted.
- `:androidApp`: Android composition root and platform adapters.

Recognition, Search and the offline-library feature modules have one-way dependency graphs. Application-domain types remain at the `:shared` binding layer when moving them lower would unnecessarily widen a feature contract.

## P2 ownership result

`FuoPlayerController` has been retired. Production navigation, screens and platform composition no longer depend on a broad compatibility facade.

App-scoped navigation is owned by `AppNavigator` / `FuoAppViewModel`. Feature state is owned by dedicated feature controllers/owners. Loading, errors and transient feedback are feature-local unless they are genuinely app-scoped.

Major migrated ownership boundaries include:

- Search and Recognition feature owners and app ports;
- Debug log and Download manager owners;
- Local Music and Local Playlist owners;
- Settings, provider authentication and onboarding owners;
- provider feature/playlist/track/media/video detail owners;
- Home/provider-content owner;
- playback queue/start/lifecycle/replacement/sleep-timer owners.

Legacy Home, Settings, Onboarding, provider-detail and controller-backed player screens were deleted after their owner-based replacements became active.

## P3-A Search physical boundary

Search is implemented by `:feature:search` rather than mutable controller/state implementations under `:shared`.

The physical module owns:

- `SearchScope`, `ProviderSearchTab` and `SearchAction`;
- `SearchFeatureState` and `SearchFeatureOwner`;
- `SearchProviderRepository` and `SearchLocalRepository` feature ports;
- `SearchResultOperations`, which supplies the minimal result semantics needed for merge/count/error handling;
- query/scope/provider selection, recognized-song query construction, loading/feedback state and search orchestration;
- Search owner tests.

The module is generic over the application's track and provider-result types. This keeps it independent from `MusicTrack`, `ProviderSearchResults`, `ProviderMusicRepository`, `LocalMusicRepository` and `RecognizedSong`, while preserving app-facing Search names through the shared integration binding.

Search UI remains in `:shared` because it uses shared Compose/design-system and cross-feature actions.

## P3-B offline-library physical boundaries

Local Playlist, Local Music and Download are extracted together because they form a small offline-library cluster but each keeps an independent physical feature boundary.

### Local Playlist

`:feature:localplaylist` owns immutable feature state and CRUD/import/export/add/remove orchestration. A feature-owned operations contract provides only the semantics needed for playlist IDs/titles/tracks, repository mutations and import/export results.

`:shared` adapts `MusicTrack`, `LocalPlaylistRepository`, `LocalPlaylistFileCodec`, provider display metadata and `AppNavigator`. The old shared `LocalPlaylistController` is retired; the app-facing controller/state names are aliases or narrow bindings rather than parallel state owners.

### Local Music

`:feature:localmusic` owns permission state, refresh concurrency, directory/filter state, metadata editing, provider metadata search and lyric saving. The module depends on feature-owned local-repository/provider ports and generic track/provider/directory/view-mode operations.

`:shared` adapts `LocalMusicRepository`, provider search/playback capabilities, application settings, navigation and `MusicTrack`. Existing timeout and stale-refresh protection remain feature-owned rather than moving into the app shell.

### Download

`:feature:download` owns download/task state, actions, parallelism and offline-library coordination. Provider resolution is expressed as a `DownloadMediaResolver`; repository access and task interpretation are feature-owned ports.

Download no longer depends directly on `LocalMusicFeatureController`. A narrow `DownloadLocalLibraryPort` exposes only permission, database readiness, media-change events and refresh. Newly completed tasks still refresh the local library, media-change events retain the 750 ms debounce, and deleting a download refreshes the local library when permission is available.

Concrete `DownloadRepository`, `ProviderMusicRepository`, settings/smart-replacement policy and `MusicTrack` stay in the `:shared` binding layer.

## Playback

Playback status, timing and transport come from `PlaybackSession`. Rich UI concerns use narrow contracts:

- `PlaybackNavigationPort` — FullPlayer and queue visibility;
- `PlaybackPresentationPort` — current presentation, seek, lyric/theme settings;
- `PlaybackQueueUiPort` — queue display/edit, source selection, shuffle/repeat and transition direction;
- `PlaybackSleepTimerPort` — sleep timer lifecycle;
- `DownloadActionPort` — download actions;
- `PlaylistActionPort` — playlist actions;
- `ProviderTrackActionPort` — provider-track navigation/actions;
- `LocalMusicActionPort` — local music actions;
- `ReplacementActionPort` — smart replacement actions.

`RuntimeMiniPlayer` and `RuntimeFullPlayer` consume these narrow contracts. Controller-backed player UI and the retired broad `PlaybackUiPort` aggregate must not be reintroduced.

## Provider and feature dependencies

New features should depend on narrow provider capability interfaces or feature-owned ports instead of the aggregate `ProviderMusicRepository` wherever a lower-level boundary has been extracted.

A feature may move to its own Gradle module only when all of its dependencies point to core/api contracts, other lower-level modules, or generic feature-owned ports bound by the application integration layer. `feature -> shared` is forbidden because it distributes the monolith rather than establishing a real boundary.

Cross-feature behavior should use the smallest stable contract. The Download/Local Music boundary is the reference example: Download asks for local-library readiness/refresh rather than depending on the Local Music controller.

## Composition roots

Android uses `AndroidAppContainer`; iOS uses `IosAppContainer`. They compose feature owners, playback runtime and app ports directly. Neither platform constructs `FuoPlayerController` or platform-local forwarding versions of Search/Recognition app ports.

`AppRoot` installs the resulting app/feature/playback graphs and renders typed routes. It does not rebuild feature business state or controller compatibility bridges.

## Architecture fitness checks

`checkArchitectureBoundaries` remains the global ownership/playback regression gate. It rejects the retired player controller, compatibility surfaces and broken Search/Recognition boundaries.

The offline-library cluster adds `checkOfflineFeatureBoundaries`, which runs with the Download feature test lifecycle and:

- requires all three physical feature modules, their owner sources/tests and shared binding files;
- rejects reintroduction of the old shared Local Playlist, Local Music and Download controller/state owners;
- rejects `:feature:localplaylist`, `:feature:localmusic` or `:feature:download` depending on `:shared`;
- uses identifier-aware matching to reject concrete shared/application dependencies from each feature's commonMain sources;
- keeps the Download-to-LocalMusic boundary narrow by rejecting a concrete `LocalMusicFeatureController` dependency in the Download module.

Android and iOS CI run Recognition, Search, Local Playlist, Local Music, Download, playback runtime and shared tests in addition to the architecture gates.

## Migration rule

Architecture changes remain behavior-preserving and independently reviewable: move ownership first, introduce narrow ports at cross-feature boundaries, then remove compatibility surfaces only after the last production caller has migrated. Physical module extraction is the final step for a feature, not a substitute for ownership isolation.

P2 sequencing and P3 progress are tracked in [`p2-architecture-roadmap.md`](p2-architecture-roadmap.md). After the offline-library cluster, provider catalog/auth is the next lower-risk physical boundary; Home remains last because it is the application-level feature aggregation surface.
