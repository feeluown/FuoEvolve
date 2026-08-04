# FuoEvolve App Links

FuoEvolve shares provider resources as GitHub Pages HTTPS links:

```text
https://feeluown.github.io/FuoEvolve/r/{provider}/{songs|playlists|artists|albums}/{id}
```

Android App Links verification requires the Digital Asset Links file at the host root:

```text
https://feeluown.github.io/.well-known/assetlinks.json
```

The copy in `docs/.well-known/assetlinks.json` is provided for GitHub Pages content and as the source file to publish in the `feeluown.github.io` organization/user Pages repository. A project Pages URL such as `https://feeluown.github.io/FuoEvolve/.well-known/assetlinks.json` is not enough for Android verification of the `feeluown.github.io` host.

The app also keeps `fuo://{provider}/{namespace}/{id}` support as a manual fallback.

## Google Android OAuth

YouTube Music uses Google's Android `AuthorizationClient`. Create an Android OAuth client in Google Cloud with:

- package name: `org.feeluown.mobile`
- SHA-1: the fingerprint of the APK signing certificate

This flow does not use a browser redirect URI, Web client ID, client secret, or GitHub Actions OAuth variable. Google Play services must be enabled, updated, and signed in on the test device.
