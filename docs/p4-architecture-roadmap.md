# P4 architecture roadmap

P4 starts after P3 physical feature modularization. P3 established one-way feature ownership; P4 reduces `:shared` as the remaining application-integration monolith without mechanically creating more feature modules.

The target dependency direction remains:

`platform composition root -> app integration -> feature owner -> stable domain/api contracts`

Lower contract/data/provider modules must never depend back on `:shared`.

## P4-A: contract ownership

In progress in this change set.

The first P4 step retires the broad `shared/.../FuoContracts.kt` aggregate and moves only contracts whose dependency direction is already stable:

- `:core:model` owns `TrackSourceType` alongside `TrackRef`;
- `:provider:api` owns provider-neutral login, capability, resource, feature, playlist/media/video and mutation value contracts;
- `:playback:api` owns playback policy, replacement-selection, resolved payload, sleep-timer and audio-format value contracts;
- `:shared` keeps the application aggregates that still depend on concrete application models, but splits them by bounded context instead of rebuilding another catch-all file.

Package names remain stable during this migration so P4-A is a behavior-neutral ownership move rather than an application-wide import rewrite.

### Intentionally deferred from P4-A

`MusicTrack` is not moved to `:core:model` yet because it currently embeds `ProviderMediaItem`. Moving it as-is would create the wrong dependency direction (`core -> provider`). The next contract step must first remove that provider-specific coupling or introduce a provider-neutral reference model.

The aggregate `ProviderMusicRepository` also remains in `:shared` because it still exposes `MusicTrack`, playback payloads and YTMusic implementation-specific OAuth types. It should be decomposed behind provider-neutral ports before moving provider runtime code downward.

Settings repositories and DataStore persistence remain in `:shared` for P4-A and move in a dedicated persistence step so serialization/migration behavior can be reviewed separately.

## P4-B: media model normalization

Goal: make the cross-feature media model independent from provider implementation models.

- replace provider-specific nested media objects in `MusicTrack` with stable references/value objects;
- preserve provider navigation metadata through explicit IDs/refs rather than provider aggregate objects;
- move the resulting stable media contracts to `:core:model`;
- migrate queue/persistence codecs without changing persisted compatibility.

Exit criterion: `:core:model` contains the stable track/media identity used across feature, playback and provider layers and has no dependency on provider/application modules.

## P4-C: provider contract/runtime split

Goal: turn the currently thin `:provider:api` into the true provider-neutral boundary and separate reusable provider runtime infrastructure from concrete providers.

- decompose `ProviderMusicRepository` into narrow capability contracts;
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
