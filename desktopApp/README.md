# Desktop app

`desktopApp` is the only FuoEvolve desktop application host. It runs the shared Compose UI on Nucleus/Tao and is distributed as a GraalVM Native Image; the legacy JVM desktop host has been removed.

## Local development

```bash
./gradlew :desktopApp:run
```

The desktop app reuses the shared UI and desktop runtime services. Provider login uses the Rust system-WebView helper. Audio and video playback use the shared Kotlin playback state machine with a thin JNI libmpv backend.

## Native Image

Build the native application image with:

```bash
./gradlew \
  -PnativeMarch=compatibility \
  :desktopApp:packageGraalvmNative
```

Nucleus exposes per-format Native Image packaging tasks. The release pipeline uses:

```bash
# Windows
./gradlew -PnativeMarch=compatibility :desktopApp:packageGraalvmNsis

# macOS
./gradlew -PnativeMarch=compatibility :desktopApp:packageGraalvmDmg

# Linux AppImage: portable/self-contained native runtime closure
./gradlew \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.bundleLinuxRuntime=true \
  :desktopApp:packageGraalvmAppImage

# Linux DEB + Pacman: distribution-managed native dependencies
./gradlew \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.bundleLinuxRuntime=false \
  :desktopApp:packageGraalvmDeb \
  :desktopApp:packageGraalvmPacman
```

CI runs the AppImage and distro-package branches in parallel. DEB and Pacman share one Native Image compilation inside the distro branch, while AppImage has its own compilation because it stages a different native runtime closure.

## Distribution matrix

| Platform | Artifact | Native dependency policy |
| --- | --- | --- |
| Windows x64 | NSIS `.exe` installer | JNI bridge, system-output capture library and pinned libmpv runtime bundled |
| macOS arm64 | DMG | JNI bridge, system-output capture library and relocatable libmpv dylib closure bundled |
| macOS x64 | DMG | JNI bridge, system-output capture library and relocatable libmpv dylib closure bundled |
| Debian/Ubuntu Linux x64 | DEB | JNI bridge/helpers bundled; libmpv, Libsecret, WebKitGTK and audio libraries supplied by APT dependencies |
| Arch Linux x64 | Pacman/Arch package | JNI bridge/helpers bundled; libmpv, Libsecret, WebKitGTK and audio libraries supplied by `pacmanDepends` |
| Portable Linux x64 | AppImage | Native audio/libmpv/Libsecret/WebKitGTK/TLS closures bundled; built against the Ubuntu 26.04 LTS baseline |

No desktop artifact bundles a JVM.

## Runtime scope

The desktop app includes:

- Nucleus/Tao windowing and Compose UI hosting;
- direct JNI libmpv audio playback;
- GPU video rendering through Tao/OpenGL on Windows/Linux and IOSurface/Metal on macOS, with software fallback;
- Windows SMTC, macOS Now Playing / Remote Command Center, and Linux MPRIS;
- close-to-tray lifecycle and single-instance activation;
- `fuo://` protocol and `.fuo` file association;
- OS-native file dialogs, clipboard integration and notifications;
- Windows Credential Manager, macOS Keychain and Linux Secret Service/Libsecret credential storage;
- system-output audio recognition using the native CPAL/JNI capture library;
- local music indexing, metadata editing, sidecar lyrics and SQLDelight listening history;
- Rust system-WebView provider login helper;
- GraalVM Native Image packaging for all supported desktop targets.

Desktop self-update is intentionally not implemented yet. Stable and Canary packages are delivered by GitHub Actions / GitHub Releases.

## Versioning

Release tags such as `1.2.3` are embedded as the desktop package version and displayed app version. Non-tagged builds identify themselves as Canary builds using the latest release version plus the current commit SHA.

## CI and release

- `.github/workflows/desktop-tests.yml` validates shared desktop/runtime tests and native resource staging on Linux, Windows and macOS.
- `.github/workflows/desktop-packaging.yml` produces the Windows NSIS installer, both macOS DMGs, and Linux AppImage/DEB/Arch packages. Linux AppImage and distro packages run in parallel on Ubuntu 26.04; DEB and Arch share one distro-package Native Image compilation.
- `master-canary.yml` publishes preview artifacts from `master` after the matching platform test workflow succeeds. iOS remains test-only and does not produce a Canary artifact.
- `release.yml` publishes the same desktop package matrix alongside Android for release tags and includes SHA-256 checksums.

Windows and macOS packages are currently published without production code signing/notarization; signing can be layered onto the same Native Image release pipeline later.