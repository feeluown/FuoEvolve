# Desktop foundation

This document records the architectural baseline for the Windows, macOS and Linux desktop ports.

## Invariants

- Desktop uses the existing Compose Multiplatform UI and enters through the common `AppRoot`; desktop must not maintain a parallel screen tree.
- Every Kotlin Multiplatform module participates in the `desktop` JVM target. The root build registers that target centrally so newly added feature/provider modules do not accidentally omit desktop.
- Desktop-only code lives at the platform composition edge (`desktopApp` and `shared/src/desktopMain`) or as platform `actual` implementations in the owning lower module.
- Feature and common code must not depend on AWT, Windows, macOS, Linux, D-Bus or native desktop APIs.
- `PlaybackSession` remains the narrow integration surface for future system media controls. Runtime state mapping and queue bridging are shared; platform hosts only select platform-specific engine/resume behavior.
- Native playback dependencies stay in `desktopApp`. `shared` consumes only the existing `PlaybackEngine` contract and must not depend on JNA or libmpv APIs.

## Current capability baseline

The desktop foundation uses the real shared application shell and now includes the first real desktop audio runtime:

- `desktopApp` hosts the common `AppRoot`.
- Provider networking and provider JVM primitives have desktop actuals.
- App settings persist in the platform-appropriate config directory.
- Cover loading, theme fallback, search history and desktop-safe UI platform hooks are implemented.
- Desktop audio playback uses libmpv through a thin direct JNA binding owned by `desktopApp`.
- The libmpv adapter supports direct/local URLs, provider HTTP headers, pause/resume, seek, EOF/error propagation and observed timeline/buffer/format/codec/bitrate state.
- Logical queue identity remains in `PlaybackState.currentTrack`; replacement/downloaded/part-specific physical identity continues through `ResolvedPlaybackSource`.
- Native libmpv loading is lazy, so application startup and JVM tests do not require the library to be installed. Development builds may use `FUOEVOLVE_LIBMPV_PATH` (or `fuoevolve.libmpv.path`) to point at a specific native library.
- Video playback, local-library indexing, downloads, audio recognition, provider web-cookie login, system media controls and tray lifecycle remain explicit unsupported adapters or follow-up integrations.

Native packaging must bundle a compatible libmpv build for each supported desktop target. Requiring end users to install a system libmpv is acceptable only for development builds, not for production packages.

## Follow-up desktop phases

1. Implement desktop local music, downloads, provider credential persistence and provider login/file integrations.
2. Add Windows SMTC, macOS Now Playing/Remote Command Center and Linux MPRIS against `PlaybackSession`.
3. Add tray lifecycle. Closing the application window must hide it; normal process exit is initiated from the tray/status item.
4. Add native packaging and platform CI for Windows, macOS and Linux, including bundled libmpv binaries.
5. Add desktop video playback on the same libmpv boundary without introducing a second media runtime.

## Linux Wayland requirement

Linux production packages must run natively under Wayland when launched from a Wayland session. Packaging must use a JetBrains Runtime with the Wayland AWT toolkit and must not depend on XWayland for the application window.

The Linux tray implementation must not force the application back to the X11 AWT tray path. It should use a Wayland-compatible desktop status-item integration (for example StatusNotifierItem/D-Bus where supported by the desktop environment). A later packaging PR must add a Wayland smoke test before Linux desktop support is considered complete.
