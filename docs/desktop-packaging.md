# Desktop packaging

`desktopApp` is the only desktop application and packaging host. Desktop artifacts are built with Nucleus/Tao and GraalVM Native Image; there is no legacy JVM desktop distribution.

## Artifact matrix

| Target | Artifact | Runtime | Native dependency policy |
| --- | --- | --- | --- |
| Windows x64 | NSIS `.exe` installer | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + pinned libmpv DLL runtime bundled |
| macOS arm64 | DMG | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + relocatable libmpv dylib closure bundled |
| macOS x64 | DMG | GraalVM Native Image | JNI libmpv bridge + native system-output capture library + relocatable libmpv dylib closure bundled |
| Debian/Ubuntu Linux x64 | DEB | GraalVM Native Image | JNI bridge/helpers bundled; libmpv, Libsecret, WebKitGTK and audio libraries supplied by APT dependencies |
| Fedora Linux x64 | RPM | GraalVM Native Image | JNI bridge/helpers bundled; libmpv, Libsecret, WebKitGTK and audio libraries supplied by `rpmRequires` |
| Arch Linux x64 | Pacman/Arch package | GraalVM Native Image | JNI bridge/helpers bundled; libmpv, Libsecret, WebKitGTK and audio libraries supplied by `pacmanDepends` |
| Portable Linux x64 | AppImage | GraalVM Native Image | bundled system-output capture library/ELF closure plus libmpv/Libsecret/WebKitGTK/TLS native closures |

No desktop artifact bundles a JVM.

## Nucleus packaging

Nucleus 2.5.15 exposes one GraalVM packaging task per format. Windows and macOS build one package branch each. Linux intentionally uses two independent branches because AppImage and distro packages have different native-dependency policies:

```bash
# Windows
./gradlew -PnativeMarch=compatibility :desktopApp:packageGraalvmNsis

# macOS
./gradlew -PnativeMarch=compatibility :desktopApp:packageGraalvmDmg

# Linux AppImage: self-contained native runtime closure
./gradlew \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.bundleLinuxRuntime=true \
  :desktopApp:packageGraalvmAppImage

# Linux DEB + RPM + Pacman: use distribution-managed native dependencies
./gradlew \
  -PnativeMarch=compatibility \
  -Pfuoevolve.nucleus.bundleLinuxRuntime=false \
  :desktopApp:packageGraalvmDeb \
  :desktopApp:packageGraalvmRpm \
  :desktopApp:packageGraalvmPacman
```

The AppImage and distro branches run in parallel in GitHub Actions. DEB, RPM and Pacman are built together inside the distro branch and therefore share one `packageGraalvmNative` dependency there. AppImage performs a separate Native Image compilation because its staged native resources intentionally include the portable dependency closure.

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
- Linux AppImage stages the portable libmpv, Libsecret, WebKitGTK, TLS and audio-capture dependency closure into the application. WebKitGTK is collected explicitly for the Nucleus provider-login WebView rather than inferred from the audio-fingerprint helper.
- Linux DEB, RPM and Arch packages stage the application-owned JNI bridge, audio-capture library and headless audio-fingerprint helper. Their external native libraries are declared as package-manager dependencies instead of copied into the package.
- Desktop system-audio recognition uses the CPAL/JNI library staged under `native/audio`; Windows and macOS capture the default output device, while Linux prefers PipeWire and falls back to a PulseAudio `.monitor` source.
- Desktop fingerprinting runs in a headless Rust helper. `afp.wasm` is embedded into the helper and executed by the Wasmi interpreter with the small legacy Embind host surface needed by `ExtractQueryFP`; the fingerprint path has no JavaScript, WebView, or WebKit runtime dependency.

## Linux LTS baseline

The Linux Native Image build is pinned to **Ubuntu 24.04 LTS as a compatibility baseline**. Both Linux packaging branches use the explicit `ubuntu-24.04` runner label and verify `VERSION_ID=24.04` before building so the native executable ABI and CPU baseline cannot silently drift with `ubuntu-latest` or a newer hosted image.

The AppImage portable closure includes libmpv, Libsecret client libraries, WebKitGTK subprocess/runtime libraries, GIO TLS support, the audio-capture closure, and their required user-space ELF dependencies. glibc-family libraries, including `libmvec`, and graphics-driver-facing libraries remain host ABI dependencies. WebKitGTK in this closure is required only by provider login.

DEB packages declare the Ubuntu 24.04 runtime packages required by the application, including `libmpv2`, `libsecret-1-0`, `libwebkit2gtk-4.1-0`, ALSA, PipeWire and PulseAudio libraries. RPM packages currently target Fedora package naming through `rpmRequires`, including `mpv-libs`, `libsecret`, `webkit2gtk4.1`, `alsa-lib`, `pipewire-libs` and `pulseaudio-libs`. Arch packages declare their equivalents through `pacmanDepends`.

Packaging also scans the extracted AppImage and DEB payloads with `readelf`. Any ELF that declares `GNU_PROPERTY_X86_ISA_1_NEEDED` above `x86-64-baseline` (v2, v3, or v4) fails the build. A future baseline upgrade must update the runner, dependency metadata, cache keys, documentation, and ISA compatibility policy together rather than automatically following the newest Ubuntu LTS.

## CI

`.github/workflows/desktop-tests.yml` runs shared desktop tests plus `desktopRuntime` and `desktopApp` tests on Linux, Windows and macOS, then compiles the platform JNI bridge and stages desktop native resources. Building the staged resources also builds the headless fingerprint helper, and CI executes the helper's fixed-vector self-test after staging.

Pull requests use runtime-level validation and do not build the full NSIS/DMG/AppImage/DEB/RPM/Pacman matrix.

`.github/workflows/desktop-packaging.yml` is the single reusable desktop packaging workflow. It builds:

1. Windows x64 NSIS installer.
2. macOS arm64 and x64 DMGs.
3. Linux x64 AppImage in a portable-runtime branch.
4. Linux x64 DEB, RPM and Arch/Pacman packages together in a system-dependency branch.

The two Linux branches execute in parallel. This restores the separation used by the earlier desktop packaging pipeline while keeping the current Nucleus Native Image implementation.

`master-canary.yml` invokes that workflow for preview builds after desktop tests complete. Android Canary packaging starts independently after Android tests; iOS remains test-only and no Canary application artifact is produced. `release.yml` invokes the same desktop workflow for release tags and publishes all seven desktop assets alongside the Android APK. The release job renames assets with the release tag and publishes `SHA256SUMS.txt`.

## Signing

Desktop release artifacts currently use the same unsigned package output as Canary. Production signing/notarization remains a separate follow-up and does not require reintroducing JVM packaging:

- Windows Authenticode for the Native Image executable, JNI/native DLLs, and NSIS installer.
- macOS Developer ID signing, hardened runtime, notarization, and stapling for both architectures.

## Linux portability

The AppImage is the explicitly portable Linux format and carries the application-managed native dependency closure. DEB, RPM and Arch integrate with their distribution package managers and do not duplicate libmpv, WebKitGTK, Libsecret or the Linux audio runtime closure inside the package. `$ORIGIN`-relative loader paths are still used for application-owned native libraries, while external runtime libraries are resolved from the host through package-manager dependencies. The audio-fingerprint helper is headless and self-contained; WebKitGTK remains solely for provider login.
