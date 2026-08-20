# Post-P1 architecture roadmap

This document defines the migration order after the playback ownership work completed in PRs #96-#100.

The architectural target remains:

`platform composition root -> app shell -> feature owner -> core/api`

`FuoPlayerController` stays available only as a compatibility facade while production screens migrate to feature-owned state/actions. The goal is not to reduce the controller by line count in isolation; the exit criterion is that production UI and cross-feature composition no longer require the facade.

## P1 closeout

P1 is considered complete when the boundaries already extracted during the first migration phase cannot silently regress back to controller forwarding.

The final closeout slice does the following:

- Search and Recognition keep their feature-owned state/controllers.
- Android and iOS stop rebuilding `SearchAppPort` / `RecognitionAppPort` objects that forward individual calls to `FuoPlayerController`.
- Shared app-shell adapters compose Search/Recognition from narrow playback/download/playlist/provider/navigation owners.
- Provider catalog data used by app-shell composition is published by `ProviderSessionRepository` instead of being read from controller facade state.
- Search result playback enters playback through the queue owner (`PlaybackQueueUiPort.playTracks`) instead of `FuoPlayerController.playFromSearch`.
- Architecture checks reject reintroduction of platform-local Search/Recognition forwarding adapters.

After this slice, new Search/Recognition work must not add controller calls back into either platform composition root.

## P2-1: remove controller ownership from app navigation and app-scoped feedback

First move app-level responsibilities that are not feature business state:

1. route activation and back-stack policy into `AppNavigator` / an app route coordinator;
2. true app-scoped transient feedback into an explicit app event owner;
3. startup/onboarding completion state into an app-owned state holder where practical.

Do not replace `FuoPlayerController` with another broad `AppController`. Prefer small app contracts that only coordinate feature owners.

Exit criteria:

- `FuoAppViewModel` does not need a concrete `FuoPlayerController` for navigation policy;
- typed routes can activate feature owners without a controller callback;
- feature loading/errors do not compete through the global `isLoading` / `message` pair.

## P2-2: migrate low-risk screens to feature-owned state/actions

Migrate production UI one bounded context at a time. Suggested order:

1. Debug logs;
2. Download manager;
3. Local music;
4. Local playlists;
5. Settings and provider authentication.

For each migrated screen:

- replace `Screen(FuoPlayerController)` with immutable UI state plus actions, or the narrow owning controller;
- move transient loading/error/feedback to that feature owner;
- add an architecture guard forbidding `FuoPlayerController` in the migrated screen/boundary;
- keep legacy facade methods only while another caller still needs them.

## P2-3: provider detail ownership

Provider details should move only after the lower-risk screens above are stable because the current controller still coordinates pagination, selected-item state and route restoration for several provider destinations.

Split state by destination rather than creating one replacement provider mega-controller:

- feature detail;
- track detail and related content;
- playlist detail and background pagination;
- media item detail;
- video detail.

Typed route payloads remain the navigation identity. Feature owners load/restore their own detail state from those payloads.

## P2-4: home/provider content ownership

Home is the highest-coupling slice and should be last among the major screens.

Move Recommend / Explore / Mine loading and selection state out of the compatibility controller, while preserving the existing incremental provider loading behavior. Avoid merging Search, provider detail and Home back into one provider facade.

Exit criteria:

- Home consumes a dedicated home/provider-content UI state;
- provider selection/filter state has one owner;
- Home refresh operations do not mutate global loading/message state.

## P2-5: retire the compatibility controller

Delete `FuoPlayerController` only after production code search shows that remaining references are compatibility tests or platform migration glue scheduled for removal.

Before removal:

- all active screens use feature/app/playback owners;
- playback runtime and player UI remain controller-free;
- Search/Recognition platform adapters remain controller-free;
- app navigation no longer delegates to the facade;
- provider detail and Home have independent state owners;
- characterization tests have moved to the new owners where appropriate.

## P2-6: physical Gradle feature modules

Only after the logical dependency graph above is stable should feature source sets become additional Gradle modules.

Likely candidates:

- `:feature:search`
- `:feature:recognition`
- `:feature:download`
- `:feature:localmusic`
- `:feature:localplaylist`
- `:feature:settings`
- later `:feature:provider-content` / `:feature:home`

Do not modularize a feature while it still depends on `FuoPlayerController`; that would turn the current monolith into a distributed monolith rather than establish a real boundary.

## Pull-request sizing rule

Keep each architecture PR behavior-preserving and independently revertible. A normal P2 PR should migrate one ownership boundary, add regression/architecture coverage for it, and remove only the compatibility surface whose last caller moved in that same PR.
