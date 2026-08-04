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

## Google browser OAuth fallback

When Google Play services cannot provide the Identity Authorization API, Android opens Google OAuth in a system browser or Custom Tab and returns through:

```text
https://feeluown.github.io/FuoEvolve/oauth2redirect
```

Create a public OAuth client that accepts this exact redirect URI, then configure its client ID without committing it to the repository:

- local build: `fuo.google.oauth.browserClientId=...` in `local.properties`
- CI build: repository variable `FUO_GOOGLE_OAUTH_BROWSER_CLIENT_ID` (the release workflows also accept a same-named secret)

The browser fallback uses Authorization Code + PKCE and does not use a client secret. The Android client used by Google Play services and this browser OAuth client are separate credentials. The root `assetlinks.json` must include the SHA-256 certificate of the installed APK so the HTTPS callback can return to the app.
