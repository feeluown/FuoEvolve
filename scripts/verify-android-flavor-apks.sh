#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 2 ]]; then
    echo "Usage: $0 <standard.apk> <smart.apk>" >&2
    exit 2
fi

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly STANDARD_APK="$1"
readonly SMART_APK="$2"
readonly EXPECTED_PACKAGE="org.feeluown.mobile"
readonly EXPECTED_ABIS="arm64-v8a x86_64"
readonly EXPECTED_MODEL_PATH="assets/smart_replacement/fuo_replacement_lite_v1.ort"
readonly EXPECTED_MODEL_SHA256="ccf14ca4aea30d29ad108a167d0c2ad1b521ab2ddac0e41038034167409ad382"
readonly EXPECTED_VOCAB_PATH="assets/smart_replacement/vocab.txt"
readonly EXPECTED_VOCAB_SHA256="45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
readonly EXPECTED_STANDARD_LABEL="FuoEvolve"
readonly EXPECTED_SMART_LABEL="FuoEvolve 智能版"
readonly WARN_SMART_APK_DELTA_BYTES=$((50 * 1024 * 1024))
readonly MAX_SMART_APK_DELTA_BYTES=$((100 * 1024 * 1024))

for apk in "$STANDARD_APK" "$SMART_APK"; do
    if [[ ! -f "$apk" ]]; then
        echo "APK does not exist: $apk" >&2
        exit 1
    fi
done

android_sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$android_sdk_root" && -f "$PROJECT_ROOT/local.properties" ]]; then
    android_sdk_root="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi

find_android_tool() {
    local tool_name="$1"
    local resolved

    if resolved="$(command -v "$tool_name" 2>/dev/null)"; then
        printf '%s\n' "$resolved"
        return
    fi
    if [[ -z "$android_sdk_root" || ! -d "$android_sdk_root/build-tools" ]]; then
        echo "Android SDK build-tools directory is unavailable" >&2
        return 1
    fi
    resolved="$(find "$android_sdk_root/build-tools" -type f -name "$tool_name" -print | sort -V | tail -n 1)"
    if [[ -z "$resolved" ]]; then
        echo "Unable to find Android tool: $tool_name" >&2
        return 1
    fi
    printf '%s\n' "$resolved"
}

AAPT="$(find_android_tool aapt)" || exit 1
APKSIGNER="$(find_android_tool apksigner)" || exit 1
readonly AAPT APKSIGNER

apk_badging() {
    "$AAPT" dump badging "$1" | sed -n '1p'
}

badging_value() {
    local badging="$1"
    local attribute="$2"
    sed -n "s/^package:.* ${attribute}='\([^']*\)'.*/\1/p" <<< "$badging"
}

apk_application_label() {
    "$AAPT" dump badging "$1" | sed -n "s/^application-label:'\(.*\)'.*/\1/p" | head -n 1
}

apk_signer() {
    "$APKSIGNER" verify --print-certs "$1" |
        sed -n 's/^.*certificate SHA-256 digest: //p' |
        sort -u |
        tr '[:upper:]' '[:lower:]'
}

apk_abis() {
    unzip -Z1 "$1" |
        sed -n 's#^lib/\([^/]*\)/.*#\1#p' |
        sort -u |
        paste -sd ' ' -
}

standard_badging="$(apk_badging "$STANDARD_APK")"
smart_badging="$(apk_badging "$SMART_APK")"
standard_package="$(badging_value "$standard_badging" name)"
smart_package="$(badging_value "$smart_badging" name)"
standard_version_code="$(badging_value "$standard_badging" versionCode)"
smart_version_code="$(badging_value "$smart_badging" versionCode)"
standard_version_name="$(badging_value "$standard_badging" versionName)"
smart_version_name="$(badging_value "$smart_badging" versionName)"
standard_signer="$(apk_signer "$STANDARD_APK")"
smart_signer="$(apk_signer "$SMART_APK")"
standard_abis="$(apk_abis "$STANDARD_APK")"
smart_abis="$(apk_abis "$SMART_APK")"
standard_label="$(apk_application_label "$STANDARD_APK")"
smart_label="$(apk_application_label "$SMART_APK")"

if [[ "$standard_package" != "$EXPECTED_PACKAGE" || "$smart_package" != "$standard_package" ]]; then
    echo "APK package mismatch: standard=$standard_package smart=$smart_package" >&2
    exit 1
fi
if [[ -z "$standard_version_code" || "$smart_version_code" != "$standard_version_code" ]]; then
    echo "APK versionCode mismatch: standard=$standard_version_code smart=$smart_version_code" >&2
    exit 1
fi
if [[ -z "$standard_version_name" || "$smart_version_name" != "${standard_version_name}-smart" ]]; then
    echo "Unexpected versionName pair: standard=$standard_version_name smart=$smart_version_name" >&2
    exit 1
fi
if [[ -z "$standard_signer" || "$smart_signer" != "$standard_signer" ]]; then
    echo "APK signer mismatch" >&2
    exit 1
fi
if [[ "$standard_abis" != "$EXPECTED_ABIS" || "$smart_abis" != "$standard_abis" ]]; then
    echo "Unexpected APK ABIs: standard=$standard_abis smart=$smart_abis" >&2
    exit 1
fi
if [[ "$standard_label" != "$EXPECTED_STANDARD_LABEL" ]]; then
    echo "Unexpected standard application label: $standard_label" >&2
    exit 1
fi
if [[ "$smart_label" != "$EXPECTED_SMART_LABEL" ]]; then
    echo "Unexpected smart application label: $smart_label" >&2
    exit 1
fi
if unzip -Z1 "$STANDARD_APK" | grep '^assets/smart_replacement/' > /dev/null; then
    echo "Standard APK contains smart replacement assets" >&2
    exit 1
fi
mapfile -t smart_models < <(
    unzip -Z1 "$SMART_APK" |
        grep '^assets/smart_replacement/.*\.ort$' || true
)
if [[ "${#smart_models[@]}" -ne 1 ]]; then
    echo "Expected exactly one ORT model in smart APK, found ${#smart_models[@]}" >&2
    printf '%s\n' "${smart_models[@]}" >&2
    exit 1
fi
if [[ "${smart_models[0]}" != "$EXPECTED_MODEL_PATH" ]]; then
    echo "Unexpected smart replacement model path: ${smart_models[0]}" >&2
    exit 1
fi
model_method="$(unzip -lv "$SMART_APK" "${smart_models[0]}" | awk -v target="${smart_models[0]}" '$NF == target { print $2 }')"
if [[ "$model_method" != "Stored" ]]; then
    echo "Smart APK ORT model must be stored without compression: ${smart_models[0]} ($model_method)" >&2
    exit 1
fi
model_sha256="$(unzip -p "$SMART_APK" "$EXPECTED_MODEL_PATH" | sha256sum | awk '{ print $1 }')"
vocab_sha256="$(unzip -p "$SMART_APK" "$EXPECTED_VOCAB_PATH" | sha256sum | awk '{ print $1 }')"
if [[ "$model_sha256" != "$EXPECTED_MODEL_SHA256" ]]; then
    echo "Unexpected smart replacement model SHA-256: $model_sha256" >&2
    exit 1
fi
if [[ "$vocab_sha256" != "$EXPECTED_VOCAB_SHA256" ]]; then
    echo "Unexpected smart replacement vocabulary SHA-256: $vocab_sha256" >&2
    exit 1
fi
if unzip -Z1 "$STANDARD_APK" | grep '^lib/[^/]*/libonnxruntime.*\.so$' > /dev/null; then
    echo "Standard APK contains ONNX Runtime native libraries" >&2
    exit 1
fi
for abi in $EXPECTED_ABIS; do
    if ! unzip -Z1 "$SMART_APK" | grep "^lib/${abi}/libonnxruntime.*\\.so$" > /dev/null; then
        echo "Smart APK is missing ONNX Runtime native library for $abi" >&2
        exit 1
    fi
done
standard_size="$(stat -c '%s' "$STANDARD_APK")"
smart_size="$(stat -c '%s' "$SMART_APK")"
smart_delta=$((smart_size - standard_size))
if (( smart_delta > MAX_SMART_APK_DELTA_BYTES )); then
    echo "Smart APK exceeds the 100 MiB size delta: $smart_delta bytes" >&2
    exit 1
fi
if (( smart_delta > WARN_SMART_APK_DELTA_BYTES )); then
    echo "Warning: Smart APK exceeds the 50 MiB target delta: $smart_delta bytes (official ORT; reduced runtime deferred)" >&2
fi

printf 'Verified standard and smart APKs: package=%s versionCode=%s ABIs=%s\n' \
    "$standard_package" \
    "$standard_version_code" \
    "$standard_abis"
