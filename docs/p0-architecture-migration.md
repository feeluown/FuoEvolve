# P0 architecture migration

This branch applies the low-risk P0 structural changes on top of the architecture-boundary work already merged in #91.

## Included

- Group shared common sources into explicit `app`, `core`, and `feature` source boundaries while keeping the Kotlin package stable.
- Keep provider protocol implementations in their existing provider adapter hierarchy.
- Move playback orchestration/UI into a dedicated playback feature directory.
- Move feature controller/state/UI files together for search, settings, local music, local playlists, downloads, recognition, provider content and home.
- Extract Android dependency construction and runtime wiring into `AndroidAppContainer`, leaving `FuoEvolveApplication` as a thin host.
- Document dependency direction and migration rules so new code does not continue expanding the flat shared root or the `FuoPlayerController` facade.

## Intentionally deferred

The remaining P0 items that require behavior changes—removing the legacy app-global loading/message facade, completing navigation ownership migration, and splitting the large contract/controller APIs—should be handled as follow-up changes after callers move to the new physical boundaries. Combining those semantic changes with a large source move would make review and rollback substantially harder.
