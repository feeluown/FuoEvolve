# FuoEvolve F-Droid repository

This directory contains the source metadata and non-secret configuration used to
generate the official binary F-Droid repository:

```text
https://feeluown.github.io/FuoEvolve/fdroid/repo/
```

Repository signing fingerprint:

```text
8D8B E45A 04CF 3242 C13B 4336 1C9F FA1C A8FB 2F39 D1A4 3CE3 5BEA DFA8 DBFE FB74
```

Generated indexes, downloaded APKs, `config.yml`, and the repository signing
keystore are build artifacts and must not be committed.

## Signing and one-time GitHub setup

The repository indexes intentionally reuse the same `fuo-evolve.jks`, alias,
and passwords as the signed Android APKs. Keep an offline backup of that keystore
and its passwords: losing or rotating it affects both APK updates and F-Droid
repository identity.

The workflow consumes the existing `FUO_SIGNING_KEYSTORE_BASE64`,
`FUO_SIGNING_STORE_PASSWORD`, `FUO_SIGNING_KEY_ALIAS`, and
`FUO_SIGNING_KEY_PASSWORD` repository secrets.

Configure GitHub Pages to use **GitHub Actions** as its source. The Environment
deployment policy must allow release tags because the release workflow runs from
the pushed tag ref.

## Publication behavior

Each release downloads up to five recent stable `arm64-v8a` APKs from GitHub
Releases, verifies their package name and signing certificate, runs
`fdroid update`, combines the generated repository with `docs/`, and deploys the
complete site to GitHub Pages.

To refresh the repository without publishing a new Android release, open
**Actions → Android Release → Run workflow**. A manual run skips the APK build
and GitHub Release job, then rebuilds the index from the existing stable
Releases and redeploys Pages.

Only the `arm64-v8a` APK is published to avoid exposing multiple APKs with the
same `versionCode` in the repository. GitHub Releases continue to provide the
`x86_64` and universal APKs.
