# Desktop packaging

This document defines the installable desktop artifact matrix and native dependency policy.

## Artifact matrix

| Target | Artifact | JVM | libmpv / native dependencies |
| --- | --- | --- | --- |
| Windows x64 | MSI + EXE | bundled JetBrains Runtime | bundled relocatable libmpv + SMTC Rust bridge |
| macOS arm64 | DMG + PKG | bundled JetBrains Runtime | bundled relocatable libmpv + Now Playing Rust bridge |
| macOS x64 | DMG + PKG | bundled JetBrains Runtime | bundled relocatable libmpv + Now Playing Rust bridge |
| Debian / Ubuntu x64 | DEB | bundled JetBrains Runtime | distribution `libmpv2` + `libsecret-1-0`; Linux tray bridge bundled |
| RPM x64 | RPM | bundled JetBrains Runtime | distribution providers of `libmpv.so.2` + `libsecret-1.so.0`; Linux tray bridge bundled |
| Arch Linux x64 | `.pkg.tar.zst` | bundled JetBrains Runtime | distribution `mpv` + `libsecret`; Linux tray bridge bundled |
| Portable Linux x64 | AppImage | bundled JetBrains Runtime | bundled libmpv/libsecret client dependency closures + Linux tray bridge |

The JVM is intentionally bundled for every platform, including distribution-native Linux packages. This keeps the Compose/AWT runtime under application control and is required for the native-Wayland runtime baseline. Linux package managers are used for native libraries where they provide a stable ABI and dependency resolution.

## Packaging profiles

`desktopApp` exposes two explicit Gradle profiles through `-Pfuoevolve.packageProfile`:

- `bundled`: stage a relocatable libmpv runtime together with the platform Rust bridge. Windows and macOS require this profile.
- `system`: stage the Linux Rust tray bridge only. libmpv and libsecret are resolved from the target distribution. This profile is Linux-only.

Bundled packaging requires `FUOEVOLVE_PACKAGE_LIBMPV_DIR` or `-Pfuoevolve.packageLibmpvDir=<path>`. The directory must contain a loadable libmpv library and all non-system native dependencies needed by that build.

## Native inputs

Immutable externally downloaded packaging tools are recorded in `desktopApp/packaging/native-deps.lock` with SHA-256 hashes.

- Windows libmpv is downloaded from a pinned `mpv-winbuild-cmake` development archive and verified before extraction.
- macOS libmpv is sourced from the runner's Homebrew bottle and converted into a relocatable dylib closure with `dylibbundler`; packaging fails if Homebrew/Cellar absolute references remain.
- AppImage tooling and its type-2 runtime are pinned by URL and SHA-256.
- Linux AppImage libmpv/libsecret inputs come from the Ubuntu build baseline, then `lddtree` collects their transitive ELF closure. glibc, the dynamic loader, and graphics-driver-facing ABI libraries remain host dependencies.

## CI

`.github/workflows/desktop-packaging.yml` builds real installable artifacts on their native operating systems:

1. Windows x64 builds the Rust SMTC bridge, prepares pinned libmpv, then runs Compose `packageMsi` and `packageExe`.
2. macOS arm64 and Intel jobs build the Now Playing bridge, create a relocatable libmpv bundle, then run `packageDmg` and `packagePkg`.
3. Ubuntu 24.04 creates a `system` Compose app image and repackages it as DEB/RPM; the same app image is passed to a clean Arch container for `makepkg`.
4. Ubuntu 22.04 creates a lower-glibc-baseline app image and converts it to an AppImage with bundled libmpv/libsecret client libraries.

Each job validates package output and dependency policy instead of treating a successful `jpackage` invocation as sufficient.

## Signing and release

PR packaging artifacts are intentionally unsigned CI validation outputs. Production release publication must add:

- Windows Authenticode signing for the application/native DLLs and installers.
- macOS Developer ID signing, hardened runtime as required, notarization, and stapling for both architectures.
- release checksums for all artifacts.

Unsigned PR artifacts must not be presented as production-ready downloads.

## Linux Wayland

Distribution and AppImage packaging must keep the Linux application window on the JetBrains Runtime Wayland toolkit and must never introduce an AWT/X11 tray fallback. Linux tray integration remains StatusNotifierItem/D-Bus. A later packaging smoke step must launch the packaged application under a native Wayland compositor and fail if it resolves to XWayland.
