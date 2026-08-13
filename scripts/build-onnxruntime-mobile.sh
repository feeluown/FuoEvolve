#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ORT_VERSION="1.29.0"
readonly ORT_TAG="v${ORT_VERSION}"
readonly WORK_DIR="${1:-$PROJECT_ROOT/build/onnxruntime-mobile}"
readonly SOURCE_DIR="$WORK_DIR/onnxruntime"
readonly PACKAGE_WORK_DIR="$WORK_DIR/package"
readonly NATIVE_BUILD_DIR="$WORK_DIR/native-build"
readonly OUTPUT_AAR="$PROJECT_ROOT/androidApp/onnxruntime-mobile/onnxruntime-mobile-android-${ORT_VERSION}.aar"
readonly CONTAINER_ENGINE="${CONTAINER_ENGINE:-podman}"
readonly NDK_VERSION="${ORT_ANDROID_NDK_VERSION:-28.0.12433566}"
ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_SDK_ROOT" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi
readonly ANDROID_SDK_ROOT

container_engine_path="$(command -v "$CONTAINER_ENGINE")"
if [[ -z "$container_engine_path" ]]; then
    echo "Container engine is unavailable: $CONTAINER_ENGINE" >&2
    exit 1
fi
if [[ ! -x "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION/ndk-build" ]]; then
    echo "Android NDK $NDK_VERSION is unavailable under $ANDROID_SDK_ROOT" >&2
    exit 1
fi

mkdir -p "$WORK_DIR" "$PACKAGE_WORK_DIR/input" "$PACKAGE_WORK_DIR/output" "$NATIVE_BUILD_DIR"
if [[ ! -d "$SOURCE_DIR/.git" ]]; then
    git clone --depth 1 --branch "$ORT_TAG" \
        https://github.com/microsoft/onnxruntime.git \
        "$SOURCE_DIR"
fi

# ONNX Runtime 1.29 requires Python 3.9+, but its custom-build image still uses
# Ubuntu 20.04/Python 3.8. The image also downloads an NDK even when a compatible
# checked local SDK is available. Patch the disposable source checkout to use
# Ubuntu 22.04 and mount the local SDK read-only.
python3 - "$SOURCE_DIR/tools/android_custom_build/Dockerfile" "$NDK_VERSION" <<'PY'
from pathlib import Path
import re
import sys

dockerfile = Path(sys.argv[1])
ndk_version = sys.argv[2]
content = dockerfile.read_text()
content = content.replace("FROM ubuntu:20.04", "FROM ubuntu:22.04", 1)
content = content.replace("ENV ANDROID_HOME=~/android-sdk", "ENV ANDROID_HOME=/home/onnxruntimedev/android-sdk", 1)
content = re.sub(r"ENV NDK_VERSION=.*", f"ENV NDK_VERSION={ndk_version}", content, count=1)
content = re.sub(
    r"RUN aria2c -q -d /tmp -o cmdline-tools\.zip.*?\"ndk;\$\{NDK_VERSION\}\"\n",
    "",
    content,
    count=1,
    flags=re.DOTALL,
)
content = content.replace(
    "RUN git clone --single-branch --branch=${ONNXRUNTIME_BRANCH_OR_TAG} --recurse-submodules",
    "RUN git -c http.version=HTTP/1.1 clone --depth=1 --single-branch "
    "--branch=${ONNXRUNTIME_BRANCH_OR_TAG} --recurse-submodules --shallow-submodules",
    1,
)
dockerfile.write_text(content)
PY

cp "$PROJECT_ROOT/androidApp/onnxruntime-mobile/ops.config" "$PACKAGE_WORK_DIR/input/"
cp "$PROJECT_ROOT/androidApp/onnxruntime-mobile/build-settings.json" "$PACKAGE_WORK_DIR/input/"

image_tag="fuo-onnxruntime-android-mobile:${ORT_VERSION}-host-sdk"
"$container_engine_path" build \
    --tag "$image_tag" \
    --file "$SOURCE_DIR/tools/android_custom_build/Dockerfile" \
    --build-arg "ONNXRUNTIME_BRANCH_OR_TAG=$ORT_TAG" \
    --build-arg "BUILD_UID=$(id -u)" \
    "$SOURCE_DIR/tools/android_custom_build"

build_volume="$NATIVE_BUILD_DIR:/workspace/build"
if [[ "$CONTAINER_ENGINE" == "podman" ]]; then
    build_volume="${build_volume}:U"
fi
"$container_engine_path" run --rm \
    --volume="$PACKAGE_WORK_DIR:/workspace/shared" \
    --volume="$build_volume" \
    --volume="$ANDROID_SDK_ROOT:/home/onnxruntimedev/android-sdk:ro" \
    --env "ANDROID_HOME=/home/onnxruntimedev/android-sdk" \
    --env "ANDROID_NDK_HOME=/home/onnxruntimedev/android-sdk/ndk/$NDK_VERSION" \
    "$image_tag" \
    /bin/bash /workspace/scripts/build.sh \
    MinSizeRel \
    /workspace/shared/output \
    /workspace/shared/input/build-settings.json \
    /workspace/shared/input/ops.config

mapfile -t built_aars < <(compgen -G "$PACKAGE_WORK_DIR/output/aar_out/*.aar" || true)
if [[ "${#built_aars[@]}" -ne 1 ]]; then
    echo "Expected exactly one reduced ONNX Runtime AAR, found ${#built_aars[@]}" >&2
    printf '%s\n' "${built_aars[@]}" >&2
    exit 1
fi

cp "${built_aars[0]}" "$OUTPUT_AAR"
printf 'Wrote %s\n' "$OUTPUT_AAR"
