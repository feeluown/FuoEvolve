# iOS Python Runtime Wiring

The iOS app hosts the shared Compose UI from the `Shared` Kotlin framework.
Provider calls are intentionally kept behind the same JSON contract used by
Android's `fuo_mobile.bridge`.

## Runtime assets

Prepare the Python resources before opening or archiving the Xcode app:

```bash
bash scripts/prepare-ios-python.sh
```

The script downloads BeeWare Python Apple Support `3.12-b8`, copies the shared
`fuo_mobile` package, and installs the same provider dependency set declared for
Android: FeelUOwn 5.1.2, NetEase, QQ Music, Bilibili, YouTube Music, and their
runtime dependencies. Generated files land under:

```text
iosApp/FuoEvolve/PythonResources/
```

Do not commit generated runtime files or downloaded archives.

## Xcode integration

`iosApp/FuoEvolve.xcodeproj` builds the Swift host and calls:

```bash
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

The shared framework exposes `MainViewController()`, which renders the Compose
`AppRoot`. The current iOS provider repository exposes provider metadata and
feature placeholders; the final online bridge still needs Kotlin/Native cinterop
against `Python.xcframework` so `IosFuoCoreBridge` can invoke
`fuo_mobile.bridge.create_bridge(...)`.

## Contract

Keep the Python-facing JSON contract identical to Android:

- `providers()` returns `{"providers": [...]}`
- `search(keyword, providerId)` returns `{"tracks": [...]}`
- `resolve(trackId, qualityPolicy, allowStandby)` returns a `PlaybackPayload`
- `features()` returns `{"features": [...]}`
- `load_feature(featureId)` returns `ProviderContentSection` fields
- `playlist_tracks(playlistId)` and `media_item_tracks(itemId)` return
  `{"tracks": [...]}`
