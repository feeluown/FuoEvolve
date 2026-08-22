# P2 architecture roadmap

P2 moved FuoEvolve from a broad controller facade to explicit app, feature and playback ownership while preserving the dependency direction:

`platform composition root -> app shell -> feature owner -> core/api`

## Closeout status

P2 ownership migration is complete. PR #104 removed the production `FuoPlayerController` dependency, controller-backed feature/player surfaces and the remaining compatibility ownership bridges. Android/iOS composition roots build the owner graph directly, and architecture checks enforce that those retired surfaces do not return.

Recognition became the first physical feature module during P2 because its dependency graph was already one-way.

## P2-1: app navigation and app-scoped state

Completed.

Navigation/back-stack policy is app-owned. Startup/onboarding state and app-scoped feedback are exposed through app state/ports rather than a concrete player controller. Feature loading/errors no longer compete through a global controller state pair.

## P2-2: low-risk feature ownership

Completed for the targeted production surfaces:

- Debug logs;
- Download manager;
- Local Music;
- Local Playlists;
- Settings/provider authentication/onboarding.

Each active screen consumes its owning state/actions or narrow ports. Retired controller screens are listed in the architecture fitness check so they cannot silently return.

## P2-3: provider detail ownership

Completed.

Provider detail state is split by destination rather than collected into another provider mega-controller:

- feature detail;
- playlist detail;
- track detail/related content;
- media-item detail;
- video detail.

Typed route payloads remain navigation identity and each owner activates/restores itself from the route.

## P2-4: Home/provider-content ownership

Completed.

Recommend/Explore/Mine content is owned by `HomeFeatureController` and related feature owners. Legacy Home controller bridges/screens have been removed. Refresh/loading/selection state is feature-local.

## P2-5: compatibility facade retirement

Completed.

`FuoPlayerController.kt` and `FuoPlayerControllerTest.kt` are deleted. The old controller-backed `PlayerScreen.kt` was decomposed into reusable controller-free player primitives before retirement. Android/iOS composition roots no longer construct or receive the facade.

The architecture gate scans production Kotlin roots globally and rejects any executable `FuoPlayerController` reference.

## P2-6: first physical feature boundary and fitness checks

Completed.

`:feature:recognition` is a real Kotlin Multiplatform feature module containing recognition contracts, state/controller implementation and tests. `:shared` consumes it through a one-way Gradle dependency and keeps only shared Compose/application integration.

## P3 physical modularization

P3 continues the final step of the migration: turning stable logical feature ownership boundaries into physical Gradle modules without introducing `feature -> shared` dependencies.

### P3-A: Search

Completed.

`:feature:search` owns Search actions, state, repository/result ports, orchestration and tests. The module is generic over application track/result types, so it does not depend on `MusicTrack`, `ProviderSearchResults`, `ProviderMusicRepository`, `LocalMusicRepository` or `RecognizedSong` from `:shared`.

`:shared` keeps the Search Compose UI and a thin `SearchFeatureBindings.kt` integration layer that binds application repositories/models to the feature-owned ports. Existing app-facing Search state/controller names are compile-time aliases rather than duplicate owners.

Architecture checks reject moving Search ownership back into `:shared`, adding `:feature:search -> :shared`, or leaking the aggregate application repositories/models into the physical Search module. Android and iOS CI both run `:feature:search:allTests`.

### P3-B: Offline library cluster

Completed as one physical-boundary change set for the three mutually related low-risk features:

- `:feature:localplaylist` owns local-playlist state, CRUD/import/export orchestration and feature tests;
- `:feature:localmusic` owns local-library refresh/filter/permission state, metadata/lyrics workflows and feature tests;
- `:feature:download` owns download state/actions, task observation and offline-library refresh coordination.

The three modules are generic over application-facing track/repository/provider/navigation types. Concrete `MusicTrack`, provider repositories, settings and `AppNavigator` are bound only in `:shared`, so none of the new feature modules depends back on `:shared`.

The previous direct `DownloadController -> LocalMusicFeatureController` coordination is replaced by a narrow local-library port. Completion-triggered refresh, media-change debounce and delete-download refresh behavior remain owned by Download without a concrete feature-to-feature dependency.

Shared Compose screens remain in `:shared`; the physical modules own business state and orchestration rather than UI styling/navigation composition. Android and iOS CI run all three feature suites, and the offline feature fitness check rejects reintroducing the retired shared owners or leaking concrete shared dependencies into the modules.

### Follow-up module candidates

Recommended order after the offline-library cluster:

1. provider catalog and provider authentication, which are narrower than provider detail;
2. provider detail ownership once catalog/auth contracts are stable;
3. Settings/onboarding after cross-feature settings contracts have narrowed;
4. Home last, because it is the application-level feature aggregation surface.

Every extraction must preserve the one-way dependency rule. A feature that still requires `:shared` remains logically isolated there until the missing contract is extracted; creating a Gradle module that depends back on `:shared` is explicitly not considered successful modularization.
