# Fuo Replacement ONNX Runtime Mobile

The smart flavor uses an extended-minimal ONNX Runtime Android package reduced
to the operators and tensor types required by
`fuo_replacement_lite_v1.ort`. The package is built from the ONNX Runtime
`v1.29.0` tag for `arm64-v8a` and `x86_64` only.

The ORT-format model and `ops.config` are generated from the pinned ONNX export
with ONNX Runtime's `convert_onnx_models_to_ort.py` using fixed optimizations
and type reduction. Rebuild the checked-in AAR with:

```shell
scripts/build-onnxruntime-mobile.sh
```

The wrapper uses ONNX Runtime's official custom Android package builder with
the checked-in `ops.config`, `build-settings.json`, and `MinSizeRel`. Run both
smart and standard APK isolation checks after replacing
`onnxruntime-mobile-android-1.29.0.aar`.
