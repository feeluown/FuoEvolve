# Nucleus desktop PoC

This module is an isolated experiment for running the existing FuoEvolve Compose UI on the Nucleus Tao desktop backend.

It is deliberately excluded from the normal Gradle project graph. Existing `desktopApp` JVM builds, packaging and CI are unchanged unless the PoC is explicitly enabled.

## JVM / Tao run

```bash
./gradlew -PenableNucleusDesktopPoc=true :desktopNucleusPoc:run
```

This uses Nucleus/Tao as the window host while still running on the regular JVM. It is useful for fast UI/runtime validation before paying the Native Image build cost.

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

The Linux executable is named `fuoevolve-nucleus-poc`. Use the packaged application folder rather than copying only the executable: Nucleus places the Skiko/AWT/native runtime sidecars required by the Compose desktop stack alongside it.

## Phase-1 scope

The PoC currently validates only the window/UI path:

- Nucleus 2.5.15 application runtime
- Tao native window backend
- existing `shared` `DesktopAppHost` and Compose UI
- GraalVM Native Image compilation and packaged native startup
- existing desktop settings/provider implementations that do not require `desktopApp` host injection

The regular desktop host integrations are intentionally not wired yet. Playback falls back to the shared unsupported engine, listening history is a no-op, local music is empty, and secure provider credential storage is not installed. Tray, system media integration, external activation and the current JNA/libmpv bridge remain owned by the existing JVM `desktopApp` path.

## CI smoke mode

`FUOEVOLVE_NUCLEUS_POC_SMOKE=1` makes the application exit shortly after the existing UI reaches composition.

The dedicated CI workflow validates both paths on Linux under Xvfb + Openbox + Mesa:

1. compile and start the Nucleus/Tao JVM host;
2. build `packageGraalvmNative` with a compatibility CPU target;
3. locate and execute the packaged native binary;
4. mount the existing `DesktopAppHost` composition before exiting.
