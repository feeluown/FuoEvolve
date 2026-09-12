# Desktop packaging

`desktopApp` is the only desktop application and packaging host. Desktop artifacts are built with Nucleus/Tao and GraalVM Native Image; there is no legacy JVM desktop distribution.

## Artifact matrix

| Target | Artifact | Runtime | Native dependency policy |
| --- | --- | --- | --- |
| Windows x64 | NSIS `.exe` installer | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + pinned libmpv DLL runtime bundled |
| macOS arm64 | DMG | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + relocatable libmpv dylib closure bundled |
| macOS x64 | DMG | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + relocatable libmpv dylib closure bundled |
| Debian/Ubuntu Linux x64 | DEB | GraalVM Native Image | shares the Linux Native Image and packaged user-space closure |
| Arch Linux x64 | Pacman/Arch package | GraalVM Native Image | shares the Linux Native Image and packaged user-space closure; `pacmanDepends` are retained as system compatibility dependencies |
| Portable Linux x64 | AppImage | GraalVM Native Image | bundled system-output capture library/ELF closure plus libmpv/Libsecret/WebKitGTK/TLS native closures |

No desktop artifact bundles a JVM.

## Nucleus packaging

Nucleus 2.5.15 exposes one GraalVM packaging task per format. The release pipeline uses the direct tasks rather than branching the workflow with GitHub Actions conditions:

```bash
# Windows
./gradlew -PnativeMarch=compatibility :desktopApp:packageGraalvmNsis

# macOS
./gradlew -PnativeMarch=compatibility :desktopApp:packageGraalvmDmg

# Linux: all direct-distribution formats in one Gradle invocation
./gradlew \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.bundleLinuxRuntime=true \
  :desktopApp:packageGraalvmAppImage \
  :desktopApp:packageGraalvmDeb \
  :desktopApp:packageGraalvmPacman
```

All three Linux packaging tasks depend on the same `packageGraalvmNative` task. Gradle executes that dependency once per invocation, so AppImage, DEB and Pacman reuse one full GraalVM Native Image compilation instead of compiling the application three times.

## Versioning

Desktop package versions are derived from release tags (`x.y.z`) unless `fuoevolve.packageVersion` / `FUOEVOLVE_PACKAGE_VERSION` explicitly overrides them.

Gradle generates `fuoevolve-desktop-version.properties` into the desktop build resources from the same version inputs used by packaging. CI exports explicit stable/Canary metadata, while local builds derive the current tag/SHA directly from Git when those overrides are absent. As a result, local `:desktopApp:run` and Native Image package builds show the same version identity they are built with:

- exact release-tag builds display the tag version, for example `1.2.3`;
- non-tagged builds display `x.y.z-canary+<short-sha>`;
- the full commit SHA and channel are retained alongside the display version.

Desktop self-update is intentionally not implemented yet. These version values are currently used for package metadata, diagnostics, and the in-app version display only.

## Native inputs

- Windows libmpv input pins live in `desktopApp/packaging/native-deps.lock`. CI verifies the pinned archive, stages the public headers/import library for JNI compilation, and bundles the runtime DLLs into the NSIS package.
- macOS uses the architecture-specific pinned mpv input and `desktopApp/packaging/macos/prepare-libmpv.sh` to produce an `@loader_path`-relative dylib closure.
- Linux AppImage, DEB and Arch packages are produced from the same staged portable user-space closure. The Arch package retains Nucleus `pacmanDepends` metadata for system-level compatibility even though runtime libraries used by the packaged helpers are staged with the application.
- Desktop system-audio recognition uses the CPAL/JNI library staged under `native/audio`; Windows and macOS capture the default output device, while Linux prefers PipeWire and falls back to a PulseAudio `.monitor` source.

## Linux LTS baseline

The Linux Native Image build is pinned to the **latest Ubuntu LTS, currently Ubuntu 26.04 LTS**. The workflow uses the explicit `ubuntu-26.04` runner label and verifies `VERSION_ID=26.04` before building so the native executable and packaged user-space ELF closure cannot silently drift with `ubuntu-latest`.

The Linux packaged closure includes libmpv, Libsecret client libraries, WebKitGTK subprocess/runtime libraries, GIO TLS support, the audio-capture closure, and their required user-space ELF dependencies. glibc and graphics-driver-facing libraries remain host ABI dependencies.

Ubuntu 26.04 is currently a public-preview GitHub-hosted runner image. This is intentional because the Linux Native Image policy is to track the newest released Ubuntu LTS rather than the `ubuntu-latest` alias. When a newer Ubuntu LTS becomes the target baseline, update the pinned runner, baseline verification, cache key, and this documentation in the same change.

## CI

`.github/workflows/desktop-tests.yml` runs shared desktop tests plus `desktopRuntime` and `desktopApp` tests on Linux, Windows and macOS, then compiles the platform JNI bridge and stages desktop native resources.

Pull requests use runtime-level validation and do not build the full NSIS/DMG/AppImage/DEB/Pacman matrix.

`.github/workflows/desktop-packaging.yml` is the single reusable desktop packaging workflow. It builds:

1. Windows x64 NSIS installer.
2. macOS arm64 and x64 DMGs.
3. Linux x64 AppImage, DEB and Arch/Pacman packages in one Ubuntu 26.04 LTS job with one Native Image compilation.

`master-canary.yml` invokes that workflow for preview builds after desktop tests complete. Android Canary packaging starts independently after Android tests; iOS remains test-only and no Canary application artifact is produced. `release.yml` invokes the same desktop workflow for release tags and publishes all six desktop assets alongside the Android APK. The release job renames assets with the release tag and publishes `SHA256SUMS.txt`.

## Signing

Desktop release artifacts currently use the same unsigned package output as Canary. Production signing/notarization remains a separate follow-up and does not require reintroducing JVM packaging:

- Windows Authenticode for the Native Image executable, JNI/native DLLs, and NSIS installer.
- macOS Developer ID signing, hardened runtime, notarization, and stapling for both architectures.

## Linux portability

All three Linux package formats use the same Native Image and packaged user-space dependency closure. The AppImage remains the explicitly portable format; DEB and Arch integrate with their distribution package managers, while the Arch package additionally carries `pacmanDepends` metadata so the target system supplies the expected desktop ABI environment. `$ORIGIN`-relative loader paths keep packaged helper libraries self-contained, while glibc and graphics-driver-facing libraries stay host-managed. The WebView helper discovers the packaged WebKitGTK runtime from `compose.application.resources.dir`, while the credential layer and system-audio capture loader discover their packaged native libraries from the same desktop resource root.