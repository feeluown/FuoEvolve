# Desktop foundation

This document records the architectural baseline for the Windows, macOS and Linux desktop ports.

## Invariants

- Desktop uses the existing Compose Multiplatform UI and enters through the common `AppRoot`; desktop must not maintain a parallel screen tree.
- Every Kotlin Multiplatform module participates in the `desktop` JVM target. The root build registers that target centrally so newly added feature/provider modules do not accidentally omit desktop.
- Desktop-only code lives at the platform composition edge (`desktopApp` and `shared/src/desktopMain`) or as platform `actual` implementations in the owning lower module.
- Feature and common code must not depend on AWT, Windows, macOS, Linux, D-Bus or native desktop APIs.
- `PlaybackSession` remains the narrow integration surface for future system media controls. Runtime state mapping and queue bridging are shared; platform hosts only select platform-specific engine/resume behavior.

## PR1 capability baseline

The foundation release is intentionally limited to making the real shared application shell compile and run on JVM Desktop:

- `desktopApp` hosts the common `AppRoot`.
- Provider networking and provider JVM primitives have desktop actuals.
- App settings persist in the platform-appropriate config directory.
- Cover loading, theme fallback, search history and desktop-safe UI platform hooks are implemented.
- Audio playback, video playback, local-library indexing, downloads, audio recognition, provider web-cookie login, system media controls and tray lifecycle are explicit unsupported adapters rather than duplicated or partial feature implementations.

The unsupported adapters are temporary platform implementations. They must be replaced behind the existing contracts; feature/UI code should not branch on desktop to work around them.

## Follow-up desktop phases

1. Implement the desktop audio runtime behind `PlaybackEngine` and replace the foundation playback adapter.
2. Implement desktop local music, downloads, provider credential persistence and provider login/file integrations.
3. Add Windows SMTC, macOS Now Playing/Remote Command Center and Linux MPRIS against `PlaybackSession`.
4. Add tray lifecycle. Closing the application window must hide it; normal process exit is initiated from the tray/status item.
5. Add native packaging and platform CI for Windows, macOS and Linux.

## Linux Wayland requirement

Linux production packages must run natively under Wayland when launched from a Wayland session. Packaging must use a JetBrains Runtime with the Wayland AWT toolkit and must not depend on XWayland for the application window.

The Linux tray implementation must not force the application back to the X11 AWT tray path. It should use a Wayland-compatible desktop status-item integration (for example StatusNotifierItem/D-Bus where supported by the desktop environment). A later packaging PR must add a Wayland smoke test before Linux desktop support is considered complete.
