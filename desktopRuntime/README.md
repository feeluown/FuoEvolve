# Desktop runtime

`desktopRuntime` contains desktop host services that must be shared by both the existing JVM `desktopApp` and the opt-in Nucleus/GraalVM desktop path.

The module is intentionally narrower than either application host. It should contain reusable desktop runtime integrations only when both hosts can use the same implementation and persistence/behavior contract.

Current responsibilities:

- OS-backed provider credential storage with the existing FuoEvolve credential key namespace.
- The desktop libmpv playback state machine and backend event contract. Queue identity, stale-event filtering, Loading/Playing transitions, pause/resume, seeking, timeline updates and audio-format reporting live here so JVM and Native Image hosts do not diverge.

Host-specific native transports remain outside this module:

- `desktopApp` keeps the existing JNA-backed libmpv transport.
- `desktopNucleusPoc` uses a thin JNI bridge for GraalVM Native Image; the native side has no callbacks into Kotlin and only exposes synchronous libmpv calls/event polling.

Windowing, tray, system media controls, video rendering and other host-specific integrations remain outside this module until their native boundaries are migrated deliberately.
