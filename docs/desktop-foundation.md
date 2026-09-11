# Desktop foundation

This document records the architectural baseline for the Windows, macOS and Linux desktop application.

## Invariants

- Desktop uses the existing Compose Multiplatform UI and enters through the common `AppRoot`; desktop must not maintain a parallel screen tree.
- Every Kotlin Multiplatform module participates in the desktop JVM target used to compile the shared code that is then closed over by GraalVM Native Image.
- `desktopApp` is the only desktop application host. There is no legacy JVM desktop distribution.
- Desktop-only code lives at the platform composition edge (`desktopApp` and `shared/src/desktopMain`) or as platform `actual` implementations in the owning lower module.
- Feature/common code must not depend directly on Windows, macOS, Linux, D-Bus, libmpv, Tao, or native desktop APIs.
- `PlaybackSession` is the system-media integration boundary. Runtime state mapping and queue bridging are shared; platform media adapters consume the session rather than binding feature code directly to libmpv.
- Native playback, video presentation, system-media, tray, notifications, activation, and secure-storage implementations are installed by `desktopApp` behind narrow shared contracts.
- Closing the desktop window must not tear down the application runtime when a usable tray/status item can restore it.

## Runtime architecture

The production host uses Nucleus/Tao and GraalVM Native Image while reusing the shared Compose application shell.

- `desktopApp` hosts `DesktopAppHost` and the common `AppRoot`.
- `desktopRuntime` owns reusable desktop runtime behavior such as libmpv playback state handling, secure credential persistence, local music services, and listening history.
- Audio playback uses a thin JNI libmpv bridge. The native side exposes synchronous libmpv operations/event polling and does not call back into Kotlin.
- Windows/Linux video renders libmpv into Tao/OpenGL GPU targets sampled by Skia.
- macOS video renders to IOSurface-backed targets imported into the Tao/Metal scene.
- Video falls back to software rendering when the platform GPU path cannot be established.
- Windows exposes system media controls through Nucleus SMTC integration.
- macOS exposes Now Playing / Remote Command Center through Nucleus media-control integration.
- Linux exposes MPRIS through Nucleus and uses native Tao Wayland windowing when running in a Wayland session.
- ComposeNativeTray provides close-to-tray behavior. Linux probes for a usable StatusNotifier watcher and keeps the main window recoverable when tray support is unavailable.
- Provider cookies, authorization headers, and OAuth credentials use Windows Credential Manager, macOS Keychain, or Linux Secret Service/Libsecret.
- Provider web login uses the packaged Rust system-WebView helper; Linux uses WebKitGTK without requiring an embedded Chromium/XWayland browser.
- Desktop audio recognition uses a native CPAL/JNI capture library. Linux prefers PipeWire and falls back to PulseAudio monitor capture.
- FileKit provides native Open/Save dialogs.
- Nucleus provides single-instance activation, `fuo://` protocol handling, `.fuo` file association, native notifications, and platform clipboard/window integration.

## Persistence compatibility

The Native Image host preserves the existing desktop data locations, bundle/application identity, and secure credential namespace so users migrating from earlier desktop builds keep settings, provider sessions, history, and local-library state where compatible.

## Packaging baseline

Desktop packages are produced only from `desktopApp`:

- Windows x64 MSI.
- macOS arm64 DMG.
- macOS x64 DMG.
- Linux x64 AppImage.
- Linux x64 Arch/Pacman package.

The AppImage is built against the pinned Ubuntu 24.04 LTS baseline and bundles its portable user-space native closure. The Arch package intentionally relies on distribution-managed native dependencies.

No desktop package contains a bundled JVM.

## Validation baseline

Desktop CI runs on Linux, Windows, and macOS and validates:

- shared desktop and `desktopRuntime` tests;
- `desktopApp` tests and Kotlin compilation;
- JNI libmpv bridge compilation;
- native audio-capture and WebView helper builds;
- packaged native-resource staging;
- platform-specific libmpv runtime preparation.

The reusable packaging workflow additionally verifies MSI/DMG/AppImage/Arch output and their required native package contents. Release tags publish the same package matrix alongside Android.

## Linux Wayland requirement

Linux production packages must use native Wayland windowing when launched in a Wayland session and must not require XWayland for the main application window. Tao/Nucleus is the desktop window backend; WebKitGTK is isolated to the provider-login helper.

The portable AppImage keeps graphics-driver-facing libraries host-managed while bundling the user-space dependency closure required by FuoEvolve.

## Deferred work

Desktop application self-update is intentionally deferred. New stable desktop versions are distributed through GitHub Releases and Canary builds through the master workflow.

Windows Authenticode signing and macOS Developer ID signing/notarization are release-hardening follow-ups and must be added to the Native Image pipeline rather than by restoring a JVM packaging path.
