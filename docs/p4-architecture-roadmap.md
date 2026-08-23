# P4 architecture roadmap

P4 starts after P3 physical feature modularization. P3 established one-way feature ownership; P4 reduces `:shared` as the remaining application-integration monolith without mechanically creating more feature modules.

The target dependency direction remains:

`platform composition root -> app integration -> feature owner -> stable domain/api contracts`

Lower contract/data/provider modules must never depend back on `:shared`.

## P4-A: contract ownership

Completed in PR #115.

The first P4 step retires the broad `shared/.../FuoContracts.kt` aggregate and moves only contracts whose dependency direction is already stable:

- `:core:model` owns `TrackSourceType` alongside `TrackRef`;
- `:provider:api` owns provider-neutral login, capability, resource, feature, playlist/video and mutation contracts;
- `:playback:api` owns playback policy, replacement-selection, resolved payload, sleep-timer and audio-format contracts;
- `:shared` keeps the application contracts that still depend on application orchestration, split by bounded context instead of rebuilding another catch-all file.

Package names remain stable during this migration so P4-A is a behavior-neutral ownership move rather than an application-wide import rewrite.

The iOS `Shared.framework` explicitly re-exports the lower public contract modules. The P4 architecture gate rejects restoring the aggregate, lower-layer back-dependencies, or removing the required Kotlin/Native exports.

## P4-B: media model normalization

Completed in PR #115 after P4-A.

The cross-feature media model is now independent from provider aggregate ownership:

- `MediaRef` / `MediaRefType` provide stable source-neutral media identity in `:core:model`;
- `MusicTrack`, local scan settings, local directory metadata and local track metadata move from `:shared` into `:core:model`;
- `MusicTrack.artistItems` now stores `List<MediaRef>` rather than a provider-owned media class;
- provider-facing `ProviderMediaItem` / `ProviderMediaItemType` names remain temporary Kotlin source-compatible type aliases to the core reference contracts, so existing concrete provider parsers do not need a large behavior-changing rewrite;
- the core reference exposes source naming as the canonical model while compatibility aliases keep provider callers source-compatible during the P4-C migration;
- typed navigation keeps its serialized `artistItems` payload but maps it to/from core refs, preserving route compatibility;
- playback queue persistence remains format `v2`; existing explicit artist/album IDs continue to decode, and a historical-v2 regression fixture protects restore compatibility.

Architecture fitness checks now also reject restoring `MediaContracts.kt`, placing provider/playback/feature dependencies under `:core:model`, reintroducing provider aggregate media types into the core media file, breaking the provider-to-core dependency, or changing the queue persistence version during this behavior-neutral step.

Exit criterion reached: `:core:model` owns the stable track/media identity used across feature, playback and provider layers and has no dependency on provider/application modules.

## P4-C: provider contract/runtime split

Goal: turn the currently thin `:provider:api` into the true provider-neutral boundary and separate reusable provider runtime infrastructure from concrete providers.

- decompose `ProviderMusicRepository` into narrow capability contracts;
- migrate provider callers from the temporary `ProviderMediaItem` compatibility aliases to canonical `MediaRef` naming where appropriate;
- move provider-neutral failures/capabilities/contracts into `:provider:api`;
- move HTTP/request/session/common mapping infrastructure into a provider runtime/core boundary;
- keep NetEase, QQ Music, Bilibili and YTMusic implementations above the API/runtime layer;
- forbid `provider:* -> shared` and `provider:* -> feature:*` dependencies.

Provider-specific physical modules are a later step only after these dependencies are one-way.

## P4-D: persistence boundaries

Goal: remove persistence implementation from app integration.

Start with settings because its lifecycle is already explicit:

- settings snapshot/contracts;
- DataStore implementation;
- migration/serialization policy;
- platform storage construction remains in composition roots.

Other local/download persistence should be extracted only where a stable repository boundary already exists.

## P4-E: app-shell cleanup and closeout

After lower contracts/provider/data boundaries stabilize:

- reduce `AppState` to genuinely app-scoped state only;
- retire remaining legacy route/integration bridges;
- rename/remove P2-era app-root compatibility naming where appropriate;
- keep Compose/navigation/application wiring above feature owners;
- extend architecture fitness checks so lower layers cannot drift back into `:shared`.

P4 is complete when `:shared` primarily contains app shell, Compose/UI and concrete application bindings, rather than generic domain models, provider-neutral contracts or persistence implementations.

## P5 direction

Only after P4 completes should concrete providers become independent physical modules such as `:provider:netease`, `:provider:qqmusic`, `:provider:bilibili` and `:provider:ytmusic`. Those modules should depend only on provider API/runtime and lower stable contracts.
