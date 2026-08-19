# Android composition root

`FuoEvolveApplication` is intentionally a thin process host. Android dependency construction, repository wiring and playback integration belong in `AndroidAppContainer`, mirroring the existing iOS `IosAppContainer` pattern.

Activities and services may obtain process-scoped entry points from the application, but new dependencies should be assembled in the container instead of being added directly to `Application`.
