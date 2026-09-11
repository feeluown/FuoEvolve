# Desktop runtime

`desktopRuntime` contains reusable desktop services consumed by the production `desktopApp` Native Image host.

The module is intentionally narrower than the application host. Shared desktop behavior belongs here when it is independent of Nucleus/Tao windowing and platform-specific native presentation boundaries.

Current responsibilities include:

- OS-backed provider credential storage with the existing FuoEvolve credential namespace.
- The desktop libmpv playback state machine and backend event contract, including queue identity, stale-event filtering, Loading/Playing transitions, pause/resume, seeking, timeline updates, audio-format reporting, and persistent playback resume behavior.
- Filesystem-backed local music and listening-history services used by the desktop composition root.

The production `desktopApp` supplies the host-specific integrations:

- a thin JNI libmpv transport for GraalVM Native Image;
- Nucleus/Tao windowing and tray lifecycle;
- Windows SMTC, macOS Now Playing / Remote Command Center, and Linux MPRIS;
- GPU video presentation through Tao/OpenGL on Windows/Linux and IOSurface/Metal on macOS, with software fallback;
- native notifications, file dialogs, clipboard integration, single-instance activation, and package associations.

There is no separate JVM desktop application host.
