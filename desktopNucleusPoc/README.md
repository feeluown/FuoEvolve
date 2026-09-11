# Nucleus desktop runtime

This module hosts the production desktop path for running the existing FuoEvolve Compose UI on Nucleus/Tao and GraalVM Native Image.

It is still enabled with `-PenableNucleusDesktopPoc=true` while the Gradle module keeps its historical name, but desktop CI no longer tests or packages the legacy JVM `desktopApp` host.

## Local run

```bash
./gradlew -PenableNucleusDesktopPoc=true :desktopNucleusPoc:run
```

The Nucleus host reuses the shared Compose UI and desktop runtime services. Provider login uses the existing Rust system-WebView helper. Playback uses the shared Kotlin playback state machine with a thin JNI libmpv backend; there is no playback sidecar process and no native-to-Kotlin callback surface.

## GraalVM Native Image

Build the packaged native application folder with:

```bash
./gradlew \
  -PenableNucleusDesktopPoc=true \
  -PnativeMarch=compatibility \
  :desktopNucleusPoc:packageGraalvmNative
```

The `graalvm-app` directory is an intermediate runtime image. User-facing installers are created by Nucleus through:

```bash
./gradlew \
  -PenableNucleusDesktopPoc=true \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.targetFormat=<msi|dmg|appimage|pacman> \
  :desktopNucleusPoc:packageGraalvmNativeDistributionForCurrentOS
```

## Distribution matrix

Desktop CI produces only Nucleus/GraalVM artifacts:

| Platform | Artifact | Native dependency policy |
| --- | --- | --- |
| Windows x64 | MSI | JNI bridge + pinned libmpv runtime bundled |
| macOS arm64 | DMG | JNI bridge + relocatable libmpv dylib closure bundled |
| macOS x64 | DMG | JNI bridge + relocatable libmpv dylib closure bundled |
| Arch Linux x64 | Pacman/Arch package | distro-managed `mpv`, `libsecret`, WebKitGTK and UI ABI dependencies |
| Portable Linux x64 | AppImage | libmpv, Libsecret client, WebKitGTK subprocess/runtime and TLS module closures bundled |

Linux AppImage runtime directories are discovered through `compose.application.resources.dir`; no wrapper-script-only environment is required for provider login or secure credential fallback.

## Current runtime scope

The Nucleus path includes:

- Nucleus 2.5.15 + Tao window backend;
- the shared `DesktopAppHost` Compose UI;
- desktop settings persistence and provider HTTP cache;
- the same OS-backed provider credential namespace used by the previous JVM host;
- the Rust system-WebView login helper as a packaged resource;
- the shared `desktopRuntime` libmpv playback state machine with persistent playback-resume state;
- direct JNI libmpv playback on Windows, macOS and Linux packaging targets;
- local-music indexing, metadata editing and sidecar lyrics through the shared desktop runtime;
- SQLDelight listening-history persistence;
- Nucleus `media-control` integration for Windows SMTC, macOS Now Playing / Remote Command Center and Linux MPRIS;
- ComposeNativeTray close-to-tray support with a safe no-tray fallback;
- Nucleus single-instance locking plus URI and `.fuo` file activation forwarding;
- `fuo://` protocol and `.fuo` file-association packaging;
- FileKit OS-native Open/Save dialogs instead of Swing file choosers;
- Nucleus native notifications for YouTube Music OAuth device codes, with dismissable notification handles;
- Tao/Compose clipboard integration for device-code copying, including the native GTK/Wayland clipboard bridge on Linux;
- stale-event correlation for rapid source replacement;
- GraalVM Native Image compilation and native installer packaging.

Remaining desktop parity work is intentionally separate: video rendering, desktop app updates, and dedicated Native Image runtime validation for microphone capture/audio recognition.

## CI

`.github/workflows/desktop-tests.yml` validates shared desktop tests, `desktopRuntime`, Nucleus tests, JNI compilation and packaged-resource staging on Linux, Windows and macOS.

`.github/workflows/desktop-packaging.yml` builds the four user-facing artifact classes above entirely through `packageGraalvmNativeDistributionForCurrentOS`. `master-canary.yml` calls that reusable workflow after the normal platform test gates.
