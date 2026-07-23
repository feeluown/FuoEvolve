# Third-party notices

## NeteaseCloudMusic-Audio-Recognize

FuoEvolve includes the audio fingerprint WebAssembly module and an adapted
browser wrapper from
[akinazuki/NeteaseCloudMusic-Audio-Recognize](https://github.com/akinazuki/NeteaseCloudMusic-Audio-Recognize)
at commit `e549f42ae70de69e85812fcffbbb81b294b3aea6`.

The upstream package identifies Aki Nazuki as its author and declares the ISC
license in its `package.json`; that pinned revision does not include a separate
license file.
The wrapper changes only the module loader and browser bridge needed to run the
same fingerprint implementation inside the Android and iOS application bundles.
