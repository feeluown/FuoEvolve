# Nucleus desktop runtime

This module is the opt-in path for running the existing FuoEvolve Compose UI on the Nucleus Tao desktop backend and GraalVM Native Image.

It remains outside the normal Gradle project graph unless `-PenableNucleusDesktopPoc=true` is supplied, so the existing `desktopApp` JVM packaging path stays available in parallel.

## JVM / Tao run

```bash
./gradlew -PenableNucleusDesktopPoc=true :desktopNucleusPoc:run
```

This uses Nucleus/Tao as the window host while still running on the regular JVM. The task builds the existing Rust system-WebView login helper and, on Linux, the thin JNI libmpv bridge used by the Nucleus playback backend.

For Linux development, install the libmpv development package so the JNI bridge can link against `libmpv`.

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

The Nucleus path now wires the runtime pieces required for provider use and Linux audio playback:

- Nucleus 2.5.15 + Tao window backend;
- existing `shared` `DesktopAppHost` and Compose UI;
- existing desktop settings persistence and provider HTTP cache;
- the same OS-backed provider credential format used by the JVM desktop app (Windows Credential Manager, macOS Keychain, Linux Secret Service/Libsecret);
- the existing Rust system-WebView login helper packaged as an app resource;
- the shared `desktopRuntime` libmpv playback state machine;
- a Linux JNI libmpv backend with no native-to-Kotlin callbacks;
- GraalVM Native Image compilation and packaged native startup.

JVM and Nucleus use the same credential key namespace, so switching runtime paths does not intentionally create a second provider login state. The JVM host continues to use the existing JNA libmpv backend while the Nucleus host uses JNI; both feed the same Kotlin playback state machine.

Linux is the first validated Nucleus native-playback target. The current Linux Nucleus package expects a compatible system `libmpv` at runtime. macOS and Windows Nucleus JNI bridge packaging are intentionally deferred until the Linux path is proven end-to-end; their existing JVM desktop playback path is unchanged.

Local music indexing, listening history, tray, external activation, video rendering, and system media integration are still owned by the existing JVM `desktopApp` path. They will be migrated behind explicit desktop runtime boundaries rather than pulling the current host implementation wholesale into Native Image.

## CI smoke modes

`FUOEVOLVE_NUCLEUS_POC_SMOKE=1` makes the application exit shortly after the existing UI reaches composition.

`FUOEVOLVE_NUCLEUS_PLAYBACK_SMOKE=/absolute/path/to/audio.wav` starts a dedicated libmpv playback probe. CI requires the shared playback state to reach `Playing` and advance beyond 300 ms before the application exits.

The dedicated Linux workflow validates the shared desktop runtime, builds and stages the Rust WebView helper and JNI libmpv bridge, runs the Tao/JVM host, builds `packageGraalvmNative`, verifies all packaged native resources, starts the packaged native binary, and finally plays a generated local WAV through the packaged Native Image + JNI + libmpv path.
