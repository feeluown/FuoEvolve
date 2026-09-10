# Nucleus desktop PoC

This module is an isolated experiment for running the existing FuoEvolve Compose UI on the Nucleus Tao desktop backend.

It is deliberately excluded from the normal Gradle project graph. Existing `desktopApp` JVM builds, packaging and CI are unchanged unless the PoC is explicitly enabled.

## Run

```bash
./gradlew -PenableNucleusDesktopPoc=true :desktopNucleusPoc:run
```

The PoC currently verifies only the phase-1 window/UI path:

- Nucleus 2.5.15 runtime
- Tao native window backend
- existing `shared` `DesktopAppHost` and Compose UI
- existing desktop settings/provider implementations that do not require `desktopApp` host injection

The regular desktop host integrations are intentionally not wired yet. Playback falls back to the shared unsupported engine, listening history is a no-op, local music is empty, and secure provider credential storage is not installed. Tray, system media integration, external activation and the current JNA/libmpv bridge remain owned by the existing JVM `desktopApp` path.

## CI smoke mode

`FUOEVOLVE_NUCLEUS_POC_SMOKE=1` makes the application exit shortly after the existing UI reaches composition. The dedicated CI workflow uses this under Xvfb to validate that the Tao runtime actually starts a window instead of only compiling the module.
