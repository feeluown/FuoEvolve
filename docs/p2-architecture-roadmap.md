# P2 architecture roadmap

P2 moves FuoEvolve from a broad controller facade to explicit app, feature and playback ownership while preserving the dependency direction:

`platform composition root -> app shell -> feature owner -> core/api`

## Closeout status

P2 ownership migration is complete when all of the following hold:

- production Kotlin code has zero `FuoPlayerController` dependencies;
- active screens consume feature/app/playback owners rather than compatibility forwarding APIs;
- Android/iOS composition roots build the owner graph directly;
- playback runtime and player UI remain controller-free;
- Search/Recognition platform forwarding shims do not return;
- at least one stable logical feature boundary is enforced as a physical Gradle feature module without a `feature -> shared` dependency;
- architecture checks and Android/iOS CI enforce those boundaries.

PR #104 implements that closeout. `FuoPlayerController`, its monolithic compatibility test, the controller-backed player screen and the remaining retired feature screens/bridges have been removed. Recognition is now owned by the physical `:feature:recognition` module and its tests run in both Android and iOS CI.

## P2-1: app navigation and app-scoped state

Completed.

Navigation/back-stack policy is app-owned. Startup/onboarding state and app-scoped feedback are exposed through app state/ports rather than a concrete player controller. Feature loading/errors no longer compete through a global controller `isLoading/message` pair.

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

## P2-6: physical feature boundary and fitness checks

Completed as the safe physical-module closeout of P2.

`:feature:recognition` is a real Kotlin Multiplatform feature module containing recognition contracts, state/controller implementation and controller tests. `:shared` consumes it through a one-way Gradle dependency and keeps only shared Compose/application integration.

Other feature implementations such as Search and Download remain logically isolated inside `:shared` for now because their current repository contracts still live in the shared graph. Moving them today would require `feature -> shared`, which is explicitly not accepted as P2 completion because it would create a distributed monolith. Their future physical extraction must first move the required repository/model contracts to lower-level API modules.

The final architecture gate also rejects:

- reintroduction of retired P2 compatibility files/screens;
- production `FuoPlayerController` references;
- retired playback aggregate compatibility types;
- controller transport calls in platform playback adapters;
- platform-local Search/Recognition forwarding objects;
- removal/move-back of the physical Recognition owner boundary.

Android and iOS CI run Recognition module tests alongside playback/shared tests and architecture checks.

## Follow-up module candidates

Future architecture work can continue with physical modules after their lower-level contracts are ready. Likely order remains:

1. `:feature:search` after search result/provider/local repository contracts no longer live in `:shared`;
2. `:feature:download` after download/provider/local repository contracts are extracted;
3. `:feature:localmusic` and `:feature:localplaylist`;
4. Settings/provider-content/Home once their shared UI/provider contracts are sufficiently narrow.

These are post-P2 modularization iterations; they must preserve the same one-way dependency rule rather than reintroduce facade-style coupling across modules.
