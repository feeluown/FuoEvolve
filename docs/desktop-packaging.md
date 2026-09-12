# Desktop packaging

`desktopApp` is the only desktop application and packaging host. Desktop artifacts are built with Nucleus/Tao and GraalVM Native Image; there is no legacy JVM desktop distribution.

## Artifact matrix

| Target | Artifact | Runtime | Native dependency policy |
| --- | --- | --- | --- |
| Windows x64 | MSI | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + pinned libmpv DLL runtime bundled |
| macOS arm64 | DMG | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + relocatable libmpv dylib closure bundled |
| macOS x64 | DMG | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + relocatable libmpv dylib closure bundled |
| Arch Linux x64 | Pacman/Arch package | GraalVM Native Image | system-output capture library bundled; `mpv`, `libsecret`, PipeWire/PulseAudio, WebKitGTK and desktop UI dependencies are distro-managed |
| Portable Linux x64 | AppImage | GraalVM Native Image | bundled system-output capture library/ELF closure plus libmpv/Libsecret/WebKitGTK/TLS native closures |

No desktop artifact bundles a JVM.

## Nucleus packaging

The host format is selected explicitly so each CI job emits only its requested installer:

```bash
./gradlew \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.targetFormat=appimage \
  :desktopApp:packageGraalvmNativeDistributionForCurrentOS
```

Supported `fuoevolve.nucleus.targetFormat` values are `msi`, `dmg`, `appimage`, and `pacman` (`arch` is accepted as an alias).

## Versioning

Desktop package versions are derived from release tags (`x.y.z`) unless `fuoevolve.packageVersion` / `FUOEVOLVE_PACKAGE_VERSION` explicitly overrides them.

Gradle generates `fuoevolve-desktop-version.properties` into the desktop build resources from the same version inputs used by packaging. CI exports explicit stable/Canary metadata, while local builds derive the current tag/SHA directly from Git when those overrides are absent. As a result, local `:desktopApp:run` and Native Image package builds show the same version identity they are built with:

- exact release-tag builds display the tag version, for example `1.2.3`;
- non-tagged builds display `x.y.z-canary+<short-sha>`;
- the full commit SHA and channel are retained alongside the display version.

Desktop self-update is intentionally not implemented yet. These version values are currently used for package metadata, diagnostics, and the in-app version display only.

## Native inputs

- Windows libmpv input pins live in `desktopApp/packaging/native-deps.lock`. CI verifies the pinned archive, stages the public headers/import library for JNI compilation, and bundles the runtime DLLs.
- macOS uses the architecture-specific pinned mpv input and `desktopApp/packaging/macos/prepare-libmpv.sh` to produce an `@loader_path`-relative dylib closure.
- Arch packages keep native libraries distribution-managed through Nucleus `pacmanDepends` metadata.
- Desktop system-audio recognition uses the CPAL/JNI library staged under `native/audio`; Windows and macOS capture the default output device, while Linux prefers PipeWire and falls back to a PulseAudio `.monitor` source.

## AppImage LTS baseline

The portable Linux build is pinned to the **latest Ubuntu LTS, currently Ubuntu 26.04 LTS**. The workflow uses the explicit `ubuntu-26.04` runner label and verifies `VERSION_ID=26.04` before building so the native executable and bundled user-space ELF closure cannot silently drift with `ubuntu-latest`.

The AppImage bundles libmpv, Libsecret client libraries, WebKitGTK subprocess/runtime libraries, GIO TLS support, the audio-capture closure, and their required user-space ELF dependencies. glibc and graphics-driver-facing libraries remain host ABI dependencies.

Ubuntu 26.04 is currently a public-preview GitHub-hosted runner image. This is intentional because the AppImage policy is to track the newest released Ubuntu LTS rather than the `ubuntu-latest` alias. When a newer Ubuntu LTS becomes the target baseline, update the pinned runner, baseline verification, cache key, and this documentation in the same change.

## CI

`.github/workflows/desktop-tests.yml` runs shared desktop tests plus `desktopRuntime` and `desktopApp` tests on Linux, Windows and macOS, then compiles the platform JNI bridge and stages desktop native resources.

Pull requests use runtime-level validation and do not build the full MSI/DMG/AppImage/Pacman matrix.

`.github/workflows/desktop-packaging.yml` is the single reusable desktop packaging workflow. It builds:

1. Windows x64 MSI.
2. macOS arm64 and x64 DMGs.
3. Linux x64 AppImage against the pinned Ubuntu 26.04 LTS baseline.
4. Linux x64 Arch/Pacman package with dependency metadata verification.

`master-canary.yml` invokes that workflow for preview builds. `release.yml` invokes the same workflow for release tags and publishes all five desktop assets alongside the Android APK. The release job renames assets with the release tag and publishes `SHA256SUMS.txt`.

## Signing

Desktop release artifacts currently use the same unsigned package output as Canary. Production signing/notarization remains a separate follow-up and does not require reintroducing JVM packaging:

- Windows Authenticode for the Native Image executable, JNI/native DLLs, and MSI.
- macOS Developer ID signing, hardened runtime, notarization, and stapling for both architectures.

## Linux portability

The Arch package intentionally relies on the target distribution package manager. The AppImage bundles its user-space native dependency closure and uses `$ORIGIN`-relative loader paths. The WebView helper discovers the packaged WebKitGTK runtime from `compose.application.resources.dir`, while the credential layer and system-audio capture loader discover their packaged native libraries from the same desktop resource root.