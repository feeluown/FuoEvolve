# Feature source layout

Feature directories are a migration boundary, not new Kotlin packages yet. Source files continue to declare `org.feeluown.mobile` so this restructuring does not change symbol visibility or require a broad import rewrite.

New feature-specific controller/state/UI files should be placed in the corresponding feature directory. Cross-feature models belong in `core/model`; reusable Compose/UI abstractions belong in `core/ui`; app shell/navigation belongs in `app`.

Do not add new feature behavior to `FuoPlayerController` unless it is playback-specific. Prefer feature-local state and narrow provider capability interfaces.
