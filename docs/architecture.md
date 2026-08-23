# FuoEvolve architecture boundaries

This document records the compile-time and ownership boundaries after P2 ownership migration, P3 physical feature modularization and P4 lower-boundary/app-shell cleanup.

## Dependency direction

The intended dependency direction is:

`platform composition root -> app/shared integration -> feature owner -> stable core/api/persistence contracts or feature-owned ports`

Platform hosts (`androidApp`, iOS host/adapters) construct platform dependencies and application bindings. Physical feature implementations must not use a platform service locator or depend back on `:shared`. Lower core/provider/playback/persistence modules likewise must not depend back on application or feature layers.

The current compile-time modules are:

- `:core:model`: stable cross-feature media/model contracts and the dependency floor.
- `:provider:api`: provider-neutral capability/result/failure contracts.
- `:provider:runtime`: reusable provider network, retry/cache, credential/cache SPI, failure mapping and concrete-provider support infrastructure.
- `:playback:api`: app-scoped playback session/value contracts.
- `:playback:runtime`: controller-free playback session state/transport implementation.
- `:persistence:settings`: stable settings storage schema and DataStore implementation boundary.
- `:feature:recognition`: Recognition contracts, state, orchestration and tests.
- `:feature:search`: Search state, repository/result ports, orchestration and tests.
- `:feature:localplaylist`: Local Playlist state and CRUD/import/export orchestration.
- `:feature:localmusic`: Local Music refresh/filter/permission/metadata/lyrics orchestration.
- `:feature:download`: Download state/actions and offline-library coordination.
- `:feature:providercatalog`: provider discovery/configuration/catalog state.
- `:feature:providerauth`: provider authentication and device-OAuth orchestration.
- `:feature:providerdetail`: provider feature/playlist/track/media/video detail orchestration.
- `:feature:settings`: Settings state/orchestration behind feature-owned collaborator ports.
- `:feature:onboarding`: startup provider-selection/rollback/completion orchestration.
- `:feature:home`: Recommend/Explore/Mine aggregation, loading and playback-intent orchestration.
- `:shared`: app shell, Compose/design primitives, concrete providers pending P5, and concrete application bindings for physical feature/lower modules.
- `:androidApp`: Android composition root and platform adapters.

All physical feature modules have one-way dependency graphs. Application-domain types remain in the `:shared` binding layer when pushing them lower would unnecessarily widen a lower contract.

## P2 ownership result

`FuoPlayerController` has been retired. Production navigation, screens and platform composition no longer depend on a broad compatibility facade.

App-scoped navigation is owned by `AppNavigator` / `FuoAppViewModel`. Feature state is owned by dedicated feature controllers/owners. Loading, errors and transient feedback are feature-local unless they are genuinely app-scoped.

Major ownership boundaries include:

- Search and Recognition;
- Debug logs and Download manager;
- Local Music and Local Playlist;
- Settings, provider authentication and onboarding;
- provider feature/playlist/track/media/video detail;
- Home/provider-content;
- playback queue/start/lifecycle/replacement/sleep-timer.

## P3 physical feature boundaries

P3 converted stable logical owners into Kotlin Multiplatform modules while keeping `feature -> shared` forbidden.

### Search

`:feature:search` owns query/scope/provider selection, recognized-song query construction, merge/deduplication, loading/feedback state and search orchestration. It is generic over application tracks/provider results. `:shared` adapts concrete provider/local repositories and keeps Compose UI.

### Offline library

`:feature:localplaylist`, `:feature:localmusic` and `:feature:download` are separate physical boundaries. Download coordinates with Local Music through a narrow local-library port rather than a concrete feature controller. Concrete repositories, provider integration and navigation stay in `:shared` or platform adapters.

### Provider Catalog/Auth

`:feature:providercatalog` owns provider discovery, enabled-provider normalization, ordering, display-scope configuration, capability/feature catalog state and session rehydration orchestration.

`:feature:providerauth` separately owns authentication input state, cookie/header login, logout/refresh feedback and the complete device-code OAuth lifecycle. Catalog and Auth do not depend on each other.

### Provider Detail

`:feature:providerdetail` contains destination-specific owners for Feature Detail, Playlist Detail, Track Detail, Media Item Detail and Video Detail. Each consumes a destination-specific capability port rather than the aggregate `ProviderMusicRepository`.

### Settings / Onboarding

`:feature:settings` owns Settings preference projection and runtime coordination through narrow preference/audio/download/cache/local-music/navigation ports.

`:feature:onboarding` separately owns startup provider-selection policy, validation, provider enablement/persistence transaction handling, rollback and completion. It reuses Settings and Provider Auth at the application integration layer instead of duplicating those domains.

### Home

`:feature:home` is the final P3 physical boundary. It owns Home business state and orchestration that previously lived in the shared `DefaultHomeFeatureController`:

- Recommend / Explore / Mine section selection;
- startup readiness gating on preferences + provider catalog;
- provider display-scope filtering and section ordering;
- incremental provider section loading;
- stale refresh suppression with per-surface generations;
- login-required and deferred-home section policy;
- Home Play All pagination and track de-duplication;
- dynamic feature first-load-and-play behavior;
- Mine playlist/favorite/content refresh coordination;
- provider playlist creation and Home-local transient loading/error feedback.

The physical Home owner depends on feature-owned contracts only:

- `HomePreferencesPort` — the Home-owned settings snapshot and mutations;
- `HomeCatalogPort` — provider/catalog readiness and provider-selection snapshot;
- `HomeContentPort` — feature loading, content semantics and provider playlist mutation;
- `HomePlaybackPort` — the narrow play-feature-tracks intent;
- `HomeLocalLibraryPort` — local playlist refresh and local-music refresh/ensure.

The module is generic over provider, feature, content, track, playlist and playback-stat representations. It does not depend on application/provider/playback aggregate models or `:shared`.

`:shared` keeps the stable concrete `HomeFeatureController` / `HomeFeatureUiState` API, Compose Home UI, app navigation/detail routing and bindings from concrete application repositories/owners to Home-owned ports. That binding is a state projection and adapter layer; it is not a second Home business owner.

## P4 lower boundaries

### Core/media contracts

`:core:model` owns stable `MediaRef`, `MusicTrack`, source identity and local-media value contracts. Provider code maps to these core identities rather than defining a second aggregate media model.

### Provider API/runtime

`:provider:api` is the provider-neutral public contract boundary. `:provider:runtime` supplies reusable transport/cache/retry/credential infrastructure and depends downward on provider/playback contracts without depending on `:shared` or feature code.

Concrete NetEase, QQ Music, Bilibili and YTMusic implementations remain in `:shared` until P5 gives them independent physical provider modules.

### Settings persistence

`:persistence:settings` owns `PersistedSettingsV1`, DataStore access and JSON persistence mechanics. Its storage schema uses primitive/collection/string representations rather than importing feature, provider, playback or application types.

`:shared` owns the application `AppSettings` read model and maps it to/from the persistence schema. The persisted file name and `app_settings_json_v1` preference key remain compatible with existing installations. Historical provider cookie/header draft fields are intentionally not part of the new schema and are never persisted by the new boundary.

Platform code only constructs the storage implementation and legacy loader; storage policy does not live in Android/iOS hosts.

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

Active-playback transaction fixes preserve the same boundary: Home and other features issue playback intent through narrow queue/playback ports; Media3 resume/session details remain inside playback runtime/platform integration.

## Provider and feature dependencies

New feature logic should depend on narrow capability interfaces or feature-owned ports instead of aggregate application repositories wherever a lower-level boundary exists.

A feature may move to its own Gradle module only when all dependencies point to core/api contracts, lower-level modules, or generic feature-owned ports bound by the application integration layer. `feature -> shared` is forbidden because it distributes the monolith rather than establishing a real boundary.

Cross-feature behavior must use the smallest stable contract. Examples:

- Download asks Local Music for readiness/refresh instead of depending on its controller.
- Provider Catalog/Auth use session/preferences ports instead of concrete provider owners.
- Provider Detail uses five destination-specific ports instead of a replacement provider mega-repository.
- Settings separates preference/audio/download/cache/local-music/navigation operations.
- Home asks for catalog/content/playback/local-library capabilities instead of depending on concrete feature controllers.

## App shell and composition roots

Android uses `AndroidAppContainer`; iOS uses `IosAppContainer`. They construct platform repositories/adapters and compose feature owners, playback runtime and app ports directly. Manual DI remains intentional; a dependency-injection framework is not required to hide this composition graph.

`AppRoot` is the canonical common Compose entry and renders typed routes. The P2-era `P2AppRoot` compatibility entry and `LegacyProviderDetailRouteBridge` are retired.

`AppUiState` is intentionally app-scoped: initialization, onboarding, theme projection and navigation back stack. It must not again become a container for the full settings model, provider sessions or feature-local state.

`FuoAppViewModel` still exposes application wiring required by `AppRoot`; feature business ownership nevertheless remains in feature owners. Future reductions should target demonstrated coupling rather than replacing explicit wiring with another broad facade.

## Architecture fitness checks

`checkArchitectureBoundaries` remains the global ownership/playback regression gate and rejects retired broad controller surfaces.

Physical feature modules add dedicated gates:

- `checkOfflineFeatureBoundaries` for Local Playlist / Local Music / Download;
- `checkProviderFeatureBoundaries` for Provider Catalog / Auth;
- `checkProviderDetailFeatureBoundaries` for Provider Detail;
- `checkSettingsFeatureBoundaries` for Settings;
- `checkOnboardingFeatureBoundaries` for Onboarding;
- `checkHomeFeatureBoundaries` for Home.

P4 additionally enforces lower-contract/runtime/persistence and app-shell closeout boundaries:

- `checkP4ContractBoundaries` rejects lower-layer back-dependencies, shared DataStore implementation leakage and restoration of retired provider/core contract infrastructure;
- `checkP4CloseoutBoundaries` rejects restoration of `P2AppRoot` / `LegacyProviderDetailRouteBridge`, aggregate settings/provider-session fields in `AppUiState`, or platform hosts routing through the old app-root entry;
- `:persistence:settings` tests protect historical settings-payload compatibility and corruption behavior.

The Home gate requires the physical module source/test and shared binding, rejects `:feature:home -> :shared`, rejects concrete application identifiers from Home commonMain and rejects restoration of the old shared Home business implementation.

Android and iOS CI run physical feature, playback runtime, provider runtime, persistence and shared tests in addition to architecture gates.

## Current phase

P2 ownership migration, P3 feature modularization and P4 lower-boundary/app-shell cleanup are complete. `:shared` is now primarily application shell/UI, concrete provider implementations pending P5, and bindings to lower feature/provider/playback/persistence boundaries.

P5 may extract concrete providers into `:provider:netease`, `:provider:qqmusic`, `:provider:bilibili` and `:provider:ytmusic`, each depending only on provider API/runtime and lower stable contracts. Do not mechanically create additional modules unless they establish a real one-way dependency or independently testable ownership boundary.

P2/P3 sequencing is tracked in [`p2-architecture-roadmap.md`](p2-architecture-roadmap.md); the completed P4 sequence is tracked in [`p4-architecture-roadmap.md`](p4-architecture-roadmap.md).
