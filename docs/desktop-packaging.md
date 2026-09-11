# Desktop packaging

Desktop CI packages the Nucleus/Tao + GraalVM Native Image runtime. The legacy JVM `desktopApp` remains in the repository during migration, but it is no longer a desktop CI test, packaging, or uploaded-artifact path.

## Artifact matrix

| Target | Artifact | Runtime | Native dependency policy |
| --- | --- | --- | --- |
| Windows x64 | MSI | GraalVM Native Image | JNI libmpv bridge + pinned libmpv DLL runtime bundled |
| macOS arm64 | DMG | GraalVM Native Image | JNI libmpv bridge + relocatable libmpv dylib closure bundled |
| macOS x64 | DMG | GraalVM Native Image | JNI libmpv bridge + relocatable libmpv dylib closure bundled |
| Arch Linux x64 | Pacman/Arch package | GraalVM Native Image | distribution `mpv`, `libsecret`, WebKitGTK and desktop UI dependencies |
| Portable Linux x64 | AppImage | GraalVM Native Image | bundled libmpv/Libsecret/WebKitGTK/TLS native closures |

There is no bundled JVM in these artifacts.

## Nucleus packaging

The host format is selected explicitly so each CI job emits only its requested installer:

```bash
./gradlew \
  -PenableNucleusDesktopPoc=true \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.targetFormat=appimage \
  :desktopNucleusPoc:packageGraalvmNativeDistributionForCurrentOS
```

Supported `fuoevolve.nucleus.targetFormat` values are `msi`, `dmg`, `appimage`, and `pacman` (`arch` is accepted as an alias).

## Native inputs

Immutable Windows libmpv inputs remain recorded in `desktopApp/packaging/native-deps.lock` while the old packaging directory is shared as a dependency-input location during migration.

- Windows uses the pinned `mpv-winbuild-cmake` development archive. CI verifies its SHA-256, extracts headers/import library for JNI compilation, and bundles the runtime DLLs.
- macOS uses the architecture-specific Homebrew mpv version pinned by the existing lock, then `dylibbundler` converts it to an `@loader_path`-relative closure before Nucleus packaging.
- Arch packages keep native libraries distro-managed through Nucleus `pacmanDepends` metadata.
- AppImage uses Ubuntu 22.04 as the lower-glibc build baseline and collects libmpv, Libsecret client, WebKitGTK subprocess, GIO TLS, and transitive ELF dependencies. glibc and graphics-driver-facing libraries remain host ABI dependencies.

## CI

`.github/workflows/desktop-tests.yml` is the only reusable desktop test workflow. It runs shared desktop tests plus `desktopRuntime` and Nucleus tests on Linux, Windows and macOS, then compiles the platform JNI bridge and stages Nucleus resources. It does not compile or test `desktopApp`.

Pull requests use that runtime-level validation only. They do not invoke GraalVM Native Image packaging and do not build MSI, DMG, AppImage, or Pacman artifacts.

`.github/workflows/desktop-packaging.yml` is the only reusable desktop packaging/upload workflow. It builds:

1. Windows x64 MSI.
2. macOS arm64 and x64 DMGs.
3. Linux x64 AppImage with portable native closure verification.
4. Linux x64 Arch/Pacman package with dependency metadata verification.

`master-canary.yml` calls this workflow after Android, desktop and iOS test gates, so every successful master build uploads the complete Nucleus desktop artifact matrix.

The earlier standalone Linux Nucleus PoC workflow and temporary DEB artifact are retired to avoid duplicate Native Image builds.

## Signing and release

Canary desktop artifacts are unsigned. Pull requests do not produce desktop installers. Production release publication should add the platform signing layer without falling back to JVM packaging:

- Windows Authenticode signing for the Native Image executable, JNI/native DLLs and MSI.
- macOS Developer ID signing, hardened runtime, notarization and stapling for both architectures.
- checksums for published artifacts.

## Linux portability

The Arch package intentionally relies on the target distribution's package manager. The AppImage intentionally bundles user-space native dependency closures and uses `$ORIGIN`-relative loader paths. The WebView helper discovers its packaged WebKitGTK runtime from `compose.application.resources.dir`, while the credential layer discovers the packaged Libsecret fallback from the same Nucleus resource root.
