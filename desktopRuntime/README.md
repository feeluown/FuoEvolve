# Desktop runtime

`desktopRuntime` contains desktop host services that must be shared by both the existing JVM `desktopApp` and the opt-in Nucleus/GraalVM desktop path.

The module is intentionally narrower than either application host. It should contain reusable desktop runtime integrations only when both hosts can use the same implementation and persistence contract.

Current responsibility:

- OS-backed provider credential storage with the existing FuoEvolve credential key namespace.

Playback, windowing, tray, system media controls, and other host-specific integrations remain outside this module until their native boundaries are migrated deliberately.
