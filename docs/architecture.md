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
- `:feature:providercatalog`: physical provider discovery/configuration/catalog state boundary.
- `:feature:providerauth`: physical provider authentication and device-OAuth orchestration boundary.
- `:feature:providerdetail`: physical provider feature/playlist/track/media/video detail orchestration boundary.
- `:shared`: app shell, shared Compose/design primitives and application bindings for physical feature modules plus feature implementations not yet physically extracted.
- `:androidApp`: Android composition root and platform adapters.

Recognition, Search, Offline Library, Provider Catalog/Auth and Provider Detail have one-way dependency graphs. Application-domain types remain at the `:shared` binding layer when moving them lower would unnecessarily widen a feature contract.

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

## P3-C Provider Catalog/Auth physical boundaries

Provider Catalog and Provider Auth are delivered together but remain separate physical modules and state owners.

### Provider Catalog

`:feature:providercatalog` owns provider discovery, enabled-provider normalization, provider ordering, per-surface provider visibility, capability/feature catalog state, loading/error state and session rehydration orchestration.

Its feature-owned contracts are limited to catalog repository operations, provider preferences and session synchronization. The module is generic over provider, feature, capability and session representations, so it does not depend on `ProviderMusicRepository`, `ProviderSessionRepository`, `AppSettingsRepository`, `ProviderInfo`, `ProviderFeature` or `ProviderCapabilities`.

`:shared` adapts those concrete application types and preserves the existing `ProviderCatalogFeatureController` API. Compatibility forwarding for the provider-normalization policy keeps existing characterization coverage while the real policy implementation remains owned by the physical feature module.

### Provider Auth

`:feature:providerauth` owns cookie/header/OAuth input state, authentication feedback, login/logout/refresh orchestration and the complete device-code OAuth lifecycle: authorization start, polling, pending/slow-down handling, timeout, cancellation, user-code presentation state and token handoff.

The module depends only on feature-owned session, device-authorization, device-code-assistant and OAuth-import ports. It is generic over application provider/auth/session types and has no dependency on concrete `ProviderMusicRepository`, `ProviderSessionRepository`, `ProviderAuthRepository`, `OAuthDeviceCodeAssistant`, `ProviderAuthState` or YTMusic implementation classes.

`:shared` binds the physical owner to the existing provider/session repositories, maps the app-facing input/UI-state models, adapts the platform device-code assistant, and keeps YTMusic-specific client-secret/oauth.json parsing outside the feature module.

The previous shared Provider Catalog owner and Provider Auth feature/legacy controller owners are retired. Catalog and Auth deliberately do not depend on each other; shared composition may observe both without creating a provider mega-controller.

## P3-D Provider Detail physical boundary

Provider Detail is implemented as one physical module, `:feature:providerdetail`, with five destination-specific owners instead of one aggregate provider-detail owner.

The module owns:

- Feature Detail page loading, merge/prefetch, dynamic-queue handling and complete-queue playback orchestration;
- Playlist Detail paging, playback-background page loading, track de-duplication, remove/delete permission policy and mutation state;
- Track Detail loading plus independent similar-track, comment and video related-content state;
- Media Item Detail track/album pagination and complete-track playback orchestration;
- Video Detail payload loading, timeout/error state and fullscreen state.

Each destination consumes a separate feature-owned capability port. The module is generic over feature, content, playlist, category, track, comment, media-item, video and playback representations. It therefore does not depend on concrete `ProviderMusicRepository`, `PlaybackQueueUiPort`, `AppSettingsRepository`, `ProviderCatalogFeatureController`, `AppNavigator`, `MusicTrack`, provider detail models or application routes.

`:shared` remains the application integration boundary. It adapts the aggregate provider repository into destination-specific ports, binds playback queue and navigation operations, persists playlist playback statistics, reads provider login/capability state, maps provider failures to user-facing errors and maps the physical feature states back to the stable app-facing models.

The existing concrete `ProviderFeatureDetailUiState`, `ProviderPlaylistDetailUiState`, `ProviderTrackDetailUiState`, `ProviderMediaItemDetailUiState` and `ProviderVideoDetailUiState` classes remain in `org.feeluown.mobile` with their existing default constructors. The controller interfaces, `ProviderDetailOwners` aggregate and `createProviderDetailOwners(...)` composition API also remain stable while their business ownership is delegated to the physical module.

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

Cross-feature behavior should use the smallest stable contract. The Download/Local Music boundary is the reference example: Download asks for local-library readiness/refresh rather than depending on the Local Music controller. Provider Catalog/Auth similarly keep session synchronization and authentication transport behind feature-owned ports instead of depending on concrete provider owners. Provider Detail uses five destination-specific ports rather than exposing `ProviderMusicRepository` or a replacement mega-repository to its physical module.

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

Provider Catalog/Auth add `checkProviderFeatureBoundaries`, wired to the Provider Auth test lifecycle. It:

- requires both provider physical modules, feature sources/tests and shared binding files;
- rejects reintroduction of the retired shared Provider Catalog/Auth owners;
- rejects either provider feature module depending on `:shared`;
- rejects concrete shared/application/provider types from each module's commonMain source.

Provider Detail adds `checkProviderDetailFeatureBoundaries`, wired to its feature test lifecycle. It:

- requires the physical module source/test and shared integration binding;
- rejects `:feature:providerdetail -> :shared`;
- rejects concrete shared/application/provider types from the physical module;
- rejects the previous shared `DefaultProvider*DetailController` business owners from returning;
- requires all five stable app-facing UiState models to remain concrete data classes rather than typealiases.

Android and iOS CI run Recognition, Search, Local Playlist, Local Music, Download, Provider Catalog, Provider Auth, Provider Detail, playback runtime and shared tests in addition to the architecture gates.

## Migration rule

Architecture changes remain behavior-preserving and independently reviewable: move ownership first, introduce narrow ports at cross-feature boundaries, then remove compatibility surfaces only after the last production caller has migrated. Physical module extraction is the final step for a feature, not a substitute for ownership isolation.

P2 sequencing and P3 progress are tracked in [`p2-architecture-roadmap.md`](p2-architecture-roadmap.md). After Provider Detail, Settings/Onboarding is the next physical boundary; Home remains last because it is the application-level feature aggregation surface.
