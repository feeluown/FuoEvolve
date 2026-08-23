# P4 architecture roadmap

P4 follows P3 physical feature modularization. P3 established one-way feature ownership; P4 reduces `:shared` as the remaining application-integration monolith without mechanically creating more feature modules.

The dependency direction remains:

`platform composition root -> app integration -> feature owner -> stable domain/api/persistence contracts`

Lower contract, persistence and provider modules must never depend back on `:shared`.

## P4-A: contract ownership

Completed in PR #115.

The first P4 step retires the broad `shared/.../FuoContracts.kt` aggregate and moves only contracts whose dependency direction is already stable:

- `:core:model` owns `TrackSourceType` alongside stable media identity;
- `:provider:api` owns provider-neutral login, capability, resource, feature, playlist/video and mutation contracts;
- `:playback:api` owns playback policy, replacement-selection, resolved payload, sleep-timer and audio-format contracts;
- `:shared` keeps application contracts that still depend on application orchestration, split by bounded context instead of rebuilding another catch-all file.

Package names remain stable during this migration so P4-A is a behavior-neutral ownership move rather than an application-wide import rewrite.

The iOS `Shared.framework` explicitly re-exports lower public contract modules. The P4 architecture gate rejects restoring the aggregate, lower-layer back-dependencies, or removing required Kotlin/Native exports.

## P4-B: media model normalization

Completed in PR #115 after P4-A.

The cross-feature media model is independent from provider aggregate ownership:

- `MediaRef` / `MediaRefType` provide stable source-neutral media identity in `:core:model`;
- `MusicTrack`, local scan settings, local directory metadata and local track metadata moved from `:shared` into `:core:model`;
- `MusicTrack.artistItems` stores `List<MediaRef>` rather than a provider-owned media class;
- provider-facing `ProviderMediaItem` / `ProviderMediaItemType` remain temporary Kotlin source-compatible aliases to the core reference contracts;
- typed navigation keeps its serialized `artistItems` payload but maps it to/from core refs, preserving route compatibility;
- playback queue persistence remains format `v2`, with regression coverage for historical restore data.

Exit criterion reached: `:core:model` owns stable track/media identity used across feature, playback and provider layers and has no dependency on provider/application modules.

## P4-C: provider contract/runtime split

Completed in PR #116.

`:provider:api` is the provider-neutral public boundary and `:provider:runtime` owns reusable provider implementation infrastructure:

- `:provider:api` owns provider registry, search, catalog/detail, library mutation and authentication capability contracts;
- provider search/content/detail result models use canonical `MediaRef` values;
- stable provider failure and video-metadata value contracts live in `:provider:api`;
- `:provider:runtime` owns HTTP/retry/cache infrastructure, persistent-cache SPI, credential SPI/value model, provider JSON/resource-key helpers, failure mapping, `BaseKotlinProvider` and the concrete-provider SPI;
- Android OkHttp and iOS Darwin provider HTTP engines live in `:provider:runtime`;
- provider runtime/network and failure-mapping tests move with the implementation instead of remaining in `:shared`;
- `:shared` keeps concrete NetEase, QQ Music, Bilibili and YTMusic providers until the P5 provider-module split;
- provider-specific OAuth/presentation details stay above the provider-neutral lower boundary.

Architecture fitness checks reject provider API/runtime back-dependencies on `:shared` or features, concrete provider types leaking into lower provider modules, restoration of the old shared runtime/network/failure files, and movement of provider-neutral capability contracts back into `:shared`.

## P4-D: settings persistence boundary

Completed in the P4 closeout change.

A physical `:persistence:settings` KMP module now owns the stable storage boundary:

- `PersistedSettingsV1` is storage-oriented and does not depend on feature, provider, playback or application types;
- enum-like values are persisted by stable names so the persistence module does not import higher-layer enums;
- `DataStoreSettingsSnapshotStore` owns DataStore access, JSON serialization and corruption/missing-payload classification;
- the historical `app_settings_json_v1` preference key and `app_settings.preferences_pb` file are preserved, so existing installations migrate without a format reset;
- historical provider cookie/header draft fields are accepted as unknown JSON fields but are intentionally absent from the new persistence schema and never written back;
- `:shared` maps between `AppSettings` as the application read model and `PersistedSettingsV1` as the storage schema;
- Android/iOS remain responsible only for constructing platform storage and legacy loaders.

Persistence tests cover historical payload compatibility, corruption handling and schema round trips. Architecture checks reject `:persistence:settings -> :shared`/feature/provider/playback/core project dependencies and DataStore implementation leakage back into shared `commonMain`.

Local/download persistence is deliberately not extracted just to create more modules; it remains future work only when a stable lower repository/storage boundary provides a concrete benefit.

## P4-E: app-shell cleanup and closeout

Completed in the P4 closeout change.

The application shell is narrowed without introducing another global coordinator:

- `AppUiState` now contains only app-scoped startup/onboarding/theme/back-stack projection rather than the full `SettingsState` and `ProviderSessionState` aggregates;
- the canonical Compose entry is `AppRoot`; Android and iOS hosts call it directly;
- the P2-era `P2AppRoot` compatibility entry is retired;
- `LegacyProviderDetailRouteBridge` is retired; typed detail routes render directly from serialized navigation payloads, while payload-less historical route-kind tokens are handled only as stale-route guards;
- feature business state remains owned by physical feature modules and application wiring remains above those owners;
- a P4 closeout verification gate prevents retired app-shell bridges from returning or aggregate settings/provider-session state from being added back to `AppUiState`.

## P4 status: complete

P4 is complete when judged by its original exit criterion: `:shared` is now primarily app shell, Compose/UI, concrete provider implementations pending P5, and application bindings. Stable cross-feature media contracts, provider-neutral API/runtime infrastructure, playback contracts/runtime, feature business owners, and settings persistence all have lower physical ownership boundaries.

The remaining breadth of `FuoAppViewModel` and platform composition roots is application wiring rather than displaced feature business ownership. Further reductions should be justified by concrete coupling or testability problems rather than by adding another generic facade or dependency-injection framework.

## P5 direction

P5 may now split concrete providers into independent physical modules such as `:provider:netease`, `:provider:qqmusic`, `:provider:bilibili` and `:provider:ytmusic`. Those modules should depend only on provider API/runtime and lower stable contracts, with `:shared` retaining application integration and provider presentation bindings.
