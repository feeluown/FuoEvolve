# FuoEvolve architecture boundaries

This document records the compile-time and ownership boundaries after the P2 migration.

## Dependency direction

The intended dependency direction is:

`platform composition root -> app shell -> feature owner -> core/api`

Platform hosts (`androidApp`, iOS host/adapters) construct platform dependencies and feature owners. Feature implementations must not use a platform service locator or depend back on `:shared`.

The current compile-time modules are:

- `:core:model`: stable cross-feature model contracts.
- `:provider:api`: provider-neutral capability contracts.
- `:playback:api`: app-scoped playback session contracts.
- `:playback:runtime`: controller-free playback session state/transport implementation.
- `:feature:recognition`: the first physical feature implementation module; it owns recognition contracts, state and controller tests and depends only on coroutines/Kotlin.
- `:shared`: app shell, shared UI/design primitives, platform-neutral adapters and feature implementations whose lower-level contracts still live in the shared graph.
- `:androidApp`: Android composition root and platform adapters.

Recognition is intentionally the first physical feature module because its dependency graph is already one-way. Search, Download, Local Music and other feature implementations remain logically owned inside `:shared` until their aggregate repository dependencies can be moved to lower-level API contracts. Creating modules that depend back on `:shared` is forbidden because that would only distribute the monolith instead of establishing a real boundary.

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

`RuntimeMiniPlayer` and `RuntimeFullPlayer` consume these narrow contracts. Controller-backed `MiniPlayer`, `FullPlayer`, queue sheets and now-playing actions were removed together with `PlayerScreen.kt`; reusable player formatting, dialogs, lyrics and transport primitives live in controller-free files.

The retired broad `PlaybackUiPort` aggregate and all controller-backed playback compatibility adapters must not be reintroduced.

## Provider and feature dependencies

New features should depend on narrow provider capability interfaces such as `ProviderSearchRepository`, `ProviderPlaybackRepository`, `ProviderAuthRepository` and provider-neutral API contracts instead of the aggregate `ProviderMusicRepository` wherever the boundary has already been extracted.

A feature may move to its own Gradle module only when all of its dependencies point to `core/api` or other lower-level modules. If moving it would require `feature -> shared`, leave it logically isolated in `:shared` and extract the missing contract first.

## Composition roots

Android uses `AndroidAppContainer`; iOS uses `IosAppContainer`. They compose feature owners, playback runtime and app ports directly. Neither platform constructs `FuoPlayerController` or platform-local forwarding versions of Search/Recognition app ports.

`AppRoot` installs the resulting app/feature/playback graphs and renders typed routes. It does not rebuild feature business state or controller compatibility bridges.

## Architecture fitness checks

`checkArchitectureBoundaries` is a P2 regression gate. It now:

- scans all production Kotlin roots in `core`, `feature`, `playback`, `provider`, `shared` and `androidApp` and rejects any executable `FuoPlayerController` reference;
- rejects reintroduction of the retired controller facade, monolithic controller test and legacy controller-backed screens/bridges;
- rejects retired playback aggregate/compatibility adapters and controller transport calls;
- rejects platform-local Search/Recognition forwarding bridges;
- requires the physical `:feature:recognition` boundary to remain present.

Android and iOS CI both run `:feature:recognition:allTests` in addition to playback/shared tests and the architecture gate.

## Migration rule

Architecture changes remain behavior-preserving and independently reviewable: move ownership first, introduce narrow ports at cross-feature boundaries, then remove compatibility surfaces only after the last production caller has migrated. Physical module extraction is the final step for a feature, not a substitute for ownership isolation.

The P2 sequencing and closeout criteria are tracked in [`p2-architecture-roadmap.md`](p2-architecture-roadmap.md).
