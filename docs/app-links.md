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
