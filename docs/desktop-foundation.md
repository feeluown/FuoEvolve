# Desktop foundation

This document records the architectural baseline for the Windows, macOS and Linux desktop ports.

## Invariants

- Desktop uses the existing Compose Multiplatform UI and enters through the common `AppRoot`; desktop must not maintain a parallel screen tree.
- Every Kotlin Multiplatform module participates in the `desktop` JVM target. The root build registers that target centrally so newly added feature/provider modules do not accidentally omit desktop.
- Desktop-only code lives at the platform composition edge (`desktopApp` and `shared/src/desktopMain`) or as platform `actual` implementations in the owning lower module.
- Feature and common code must not depend on AWT, Windows, macOS, Linux, D-Bus or native desktop APIs.
- `PlaybackSession` is the system-media integration boundary. Runtime state mapping and queue bridging are shared; platform media adapters consume the session rather than binding directly to libmpv.
- Native playback, system-media, tray and OS secure-storage dependencies stay in `desktopApp`. `shared` consumes narrow contracts such as `PlaybackEngine`, `ProviderCredentialStore` and `LocalMusicRepository` and must not depend on JNA, libmpv, WinRT, MediaPlayer.framework or D-Bus APIs.
- Closing a desktop window must not tear down the application runtime when a usable tray/status item can restore it. Normal application exit is owned by the tray/status-item action.

## Current capability baseline

The desktop foundation uses the real shared application shell and now includes working audio playback, downloads, secure provider credentials, local-library indexing, system media controls and close-to-tray lifecycle:

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
- Windows and macOS use their AWT-backed native system tray/status-item integration. Closing the window hides it without disposing `DesktopAppHost`, so playback, downloads and system-media sessions continue; Show restores/focuses the existing window and Exit terminates the application.
- Linux uses a Rust `ksni` StatusNotifierItem/D-Bus bridge rather than AWT `SystemTray`, avoiding an X11/XWayland tray dependency. If no StatusNotifier watcher is available, or if the bridge cannot load, the application keeps the window visible instead of hiding it into an unrecoverable state. `FUOEVOLVE_LINUX_TRAY_BRIDGE_PATH` can override the development bridge location.

Native packaging must bundle compatible libmpv and platform bridge binaries for each supported desktop target. Requiring end users to install system libmpv or the Rust bridge libraries is acceptable only for development builds, not for production packages.

## Validation baseline

Desktop CI compiles the real platform adapters rather than relying on cross-platform stubs:

- Ubuntu builds the Rust StatusNotifier tray `cdylib`, then runs `:desktopApp:compileKotlin`, `:desktopApp:test` and `:shared:desktopTest`.
- Windows builds the Rust SMTC `cdylib`, then compiles/tests the Windows Kotlin/JNA desktop graph.
- macOS builds the Rust Now Playing `cdylib` on a real macOS runner, then compiles/tests the macOS Kotlin/JNA desktop graph.
- Desktop tray tests cover OS backend selection, close-to-tray policy and graceful controller creation/cleanup when the platform integration is unavailable.
- Android architecture, unit, coverage and PR compile validation continue to run against desktop changes to prevent dependency leakage or shared regressions.

The system-media and tray-lifecycle phases are complete for the three desktop targets. Remaining work is primarily packaging/runtime distribution and capabilities that still have explicit unsupported adapters.

## Follow-up desktop phases

1. Complete provider login integrations that require embedded browser cookie capture or other platform-specific handoff; keep the Linux Wayland requirement when selecting any embedded browser technology.
2. Add native packaging for Windows, macOS and Linux, including bundled libmpv and the Rust platform bridge binaries.
3. Add the Linux native-Wayland packaging/smoke test and verify the packaged tray integration does not force XWayland.
4. Add desktop video playback on the same libmpv boundary without introducing a second media runtime.
5. Add remaining desktop-only capabilities such as audio recognition where they provide product value.

## Linux Wayland requirement

Linux production packages must run natively under Wayland when launched from a Wayland session. Packaging must use a JetBrains Runtime with the Wayland AWT toolkit and must not depend on XWayland for the application window.

Linux tray lifecycle already uses StatusNotifierItem/D-Bus rather than the X11 AWT tray path. The packaging phase must preserve that behavior and add a native-Wayland smoke test before Linux desktop support is considered complete.
