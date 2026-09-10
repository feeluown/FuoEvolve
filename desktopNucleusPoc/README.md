# Nucleus desktop runtime

This module is the opt-in path for running the existing FuoEvolve Compose UI on the Nucleus Tao desktop backend and GraalVM Native Image.

It remains outside the normal Gradle project graph unless `-PenableNucleusDesktopPoc=true` is supplied, so the existing `desktopApp` JVM packaging path stays available in parallel.

## JVM / Tao run

```bash
./gradlew -PenableNucleusDesktopPoc=true :desktopNucleusPoc:run
```

This uses Nucleus/Tao as the window host while still running on the regular JVM. The task also builds the existing Rust system-WebView login helper so provider web login can be exercised locally.

## GraalVM Native Image

Build the packaged native application folder with:

```bash
./gradlew \
  -PenableNucleusDesktopPoc=true \
  -PnativeMarch=compatibility \
  :desktopNucleusPoc:packageGraalvmNative
```

The packaged output is written below:

```text
desktopNucleusPoc/build/compose/binaries/**/graalvm-app/
```

The Linux executable is named `fuoevolve-nucleus-poc`. Use the complete packaged application folder rather than copying only the executable: Nucleus places the Skiko/AWT/native runtime sidecars and FuoEvolve app resources alongside it.

## Current usable scope

The Nucleus path now wires the runtime pieces required for browsing providers with persistent authentication:

- Nucleus 2.5.15 + Tao window backend;
- existing `shared` `DesktopAppHost` and Compose UI;
- existing desktop settings persistence and provider HTTP cache;
- the same OS-backed provider credential format used by the JVM desktop app (Windows Credential Manager, macOS Keychain, Linux Secret Service/Libsecret);
- the existing Rust system-WebView login helper packaged as an app resource;
- GraalVM Native Image compilation and packaged native startup.

JVM and Nucleus use the same credential key namespace, so switching runtime paths does not intentionally create a second provider login state.

Playback, local music, listening history, tray, external activation, and system media integration are still owned by the existing JVM `desktopApp` path. They will be migrated behind explicit desktop runtime boundaries rather than pulling the current JNA/dbus/libmpv host implementation wholesale into Native Image.

## CI smoke mode

`FUOEVOLVE_NUCLEUS_POC_SMOKE=1` makes the application exit shortly after the existing UI reaches composition.

The dedicated Linux workflow now validates the shared credential runtime, builds and stages the Rust WebView helper, runs the Tao/JVM host, builds `packageGraalvmNative`, verifies the helper is present in the packaged app resources, and starts the packaged native binary.
