# Desktop foundation

This document records the architectural baseline for the Windows, macOS and Linux desktop ports.

## Invariants

- Desktop uses the existing Compose Multiplatform UI and enters through the common `AppRoot`; desktop must not maintain a parallel screen tree.
- Every Kotlin Multiplatform module participates in the `desktop` JVM target. The root build registers that target centrally so newly added feature/provider modules do not accidentally omit desktop.
- Desktop-only code lives at the platform composition edge (`desktopApp` and `shared/src/desktopMain`) or as platform `actual` implementations in the owning lower module.
- Feature and common code must not depend on AWT, Windows, macOS, Linux, D-Bus or native desktop APIs.
- `PlaybackSession` is the system-media integration boundary. Runtime state mapping and queue bridging are shared; platform media adapters consume the session rather than binding directly to libmpv.
- Native playback, system-media and OS secure-storage dependencies stay in `desktopApp`. `shared` consumes narrow contracts such as `PlaybackEngine`, `ProviderCredentialStore` and `LocalMusicRepository` and must not depend on JNA, libmpv, WinRT, MediaPlayer.framework or D-Bus APIs.

## Current capability baseline

The desktop foundation uses the real shared application shell and now includes working audio playback, downloads, secure provider credentials, local-library indexing and system media controls:

- `desktopApp` hosts the common `AppRoot`.
- Provider networking and provider JVM primitives have desktop actuals.
- App settings persist in the platform-appropriate config directory.
- Provider cookies, authorization headers and OAuth credentials persist through the operating-system secure store: Windows Credential Manager, macOS Keychain, and Linux Secret Service/Libsecret when available. No plaintext credential fallback is used.
- Large provider credentials are split into bounded secure-store entries and committed through a generation manifest so an interrupted update keeps the previous login state readable.
- YT Music device OAuth uses the system browser plus desktop clipboard/notification helpers; it does not require an embedded Chromium/XWayland browser.
- Cover loading, theme fallback, search history and desktop-safe UI platform hooks are implemented.
- Desktop audio playback uses libmpv through a thin direct JNA binding owned by `desktopApp`.
- The libmpv adapter supports direct/local URLs, provider HTTP headers, pause/resume, seek, EOF/error propagation and observed timeline/buffer/format/codec/bitrate state.
- Logical queue identity remains in `PlaybackState.currentTrack`; replacement/downloaded/part-specific physical identity continues through `ResolvedPlaybackSource`.
- Delayed libmpv events from a replaced source are isolated with `playlist_entry_id`, preventing stale EOF/error/progress events from mutating the current playback transaction.
- Native libmpv loading is lazy, so application startup and JVM tests do not require the library to be installed. Development builds may use `FUOEVOLVE_LIBMPV_PATH` (or `fuoevolve.libmpv.path`) to point at a specific native library.
- Desktop downloads reuse the shared download feature and provide persisted tasks, configurable parallelism, pause/resume/retry/delete, provider HTTP headers, Range resume with ETag/Last-Modified validation, progress checkpoints and local `file://` playback reuse.
- Desktop local music scans the standard Music/XDG locations, persists its index, reads and edits audio tags, supports duration/exclusion filters and `.lrc` sidecars, and feeds local `file://` sources into the same libmpv runtime.
- Linux exposes MPRIS v2 over D-Bus, including transport controls, metadata, position, seek and dynamic capabilities.
- Windows exposes SMTC through a Rust `cdylib` using the published `windows` crate. WinRT/COM lifetime stays in Rust and the JVM only sees a narrow JNA/C ABI.
- macOS exposes Now Playing / Remote Command Center through a Rust `cdylib` backed by `playwire`; play/pause/stop/next/previous/seek events map back to `PlaybackSession`, while playback state, timeline and metadata flow from the same session.

Native packaging must bundle compatible libmpv and platform bridge binaries for each supported desktop target. Requiring end users to install system libmpv is acceptable only for development builds, not for production packages.

## Validation baseline

Desktop CI compiles the real platform adapters rather than relying on cross-platform stubs:

- Ubuntu runs `:desktopApp:compileKotlin`, `:desktopApp:test` and `:shared:desktopTest`.
- Windows builds the Rust SMTC `cdylib`, then compiles/tests the Windows Kotlin/JNA desktop graph.
- macOS builds the Rust Now Playing `cdylib` on a real macOS runner, then compiles/tests the macOS Kotlin/JNA desktop graph.
- Android architecture, unit, coverage and PR compile validation continue to run against desktop changes to prevent dependency leakage or shared regressions.

## Follow-up desktop phases

1. Complete provider login integrations that require embedded browser cookie capture or other platform-specific handoff; keep the Linux Wayland requirement when selecting any embedded browser technology.
2. Add tray/status-item lifecycle. Closing the application window must hide it; normal process exit is initiated from the tray/status item.
3. Add native packaging for Windows, macOS and Linux, including bundled libmpv and the Rust platform bridge binaries.
4. Add the Linux native-Wayland packaging/smoke test and verify tray integration does not force XWayland.
5. Add desktop video playback on the same libmpv boundary without introducing a second media runtime.
6. Add remaining desktop-only capabilities such as audio recognition where they provide product value.

## Linux Wayland requirement

Linux production packages must run natively under Wayland when launched from a Wayland session. Packaging must use a JetBrains Runtime with the Wayland AWT toolkit and must not depend on XWayland for the application window.

The Linux tray implementation must not force the application back to the X11 AWT tray path. It should use a Wayland-compatible desktop status-item integration (for example StatusNotifierItem/D-Bus where supported by the desktop environment). A packaging PR must add a Wayland smoke test before Linux desktop support is considered complete.
