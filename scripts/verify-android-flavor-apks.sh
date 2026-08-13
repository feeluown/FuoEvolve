#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 3 ]]; then
    echo "Usage: $0 <standard.apk> <smart-arm64-v8a.apk> <smart-x86_64.apk>" >&2
    exit 2
fi

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly STANDARD_APK="$1"
readonly SMART_ARM64_APK="$2"
readonly SMART_X86_64_APK="$3"
readonly EXPECTED_PACKAGE="org.feeluown.mobile"
readonly EXPECTED_STANDARD_ABIS="arm64-v8a x86_64"
readonly EXPECTED_MODEL_PATH="assets/smart_replacement/fuo_replacement_lite_v1.ort"
readonly EXPECTED_MODEL_SHA256="ccf14ca4aea30d29ad108a167d0c2ad1b521ab2ddac0e41038034167409ad382"
readonly EXPECTED_VOCAB_PATH="assets/smart_replacement/vocab.txt"
readonly EXPECTED_VOCAB_SHA256="45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
readonly EXPECTED_LABEL="FuoEvolve"
readonly WARN_SMART_APK_DELTA_BYTES=$((50 * 1024 * 1024))
readonly MAX_SMART_APK_DELTA_BYTES=$((80 * 1024 * 1024))

for apk in "$STANDARD_APK" "$SMART_ARM64_APK" "$SMART_X86_64_APK"; do
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

verify_smart_apk() {
    local apk="$1"
    local expected_abi="$2"
    local standard_package="$3"
    local standard_version_code="$4"
    local standard_version_name="$5"
    local standard_signer="$6"
    local standard_size="$7"
    local badging package version_code version_name signer abis label
    local smart_models model_method model_sha256 vocab_sha256 smart_size smart_delta

    badging="$(apk_badging "$apk")"
    package="$(badging_value "$badging" name)"
    version_code="$(badging_value "$badging" versionCode)"
    version_name="$(badging_value "$badging" versionName)"
    signer="$(apk_signer "$apk")"
    abis="$(apk_abis "$apk")"
    label="$(apk_application_label "$apk")"

    if [[ "$package" != "$standard_package" ]]; then
        echo "Smart APK package mismatch: $package" >&2
        exit 1
    fi
    if [[ "$version_code" != "$standard_version_code" ]]; then
        echo "Smart APK versionCode mismatch for $expected_abi: $version_code" >&2
        exit 1
    fi
    if [[ "$version_name" != "${standard_version_name}-smart" ]]; then
        echo "Unexpected smart versionName for $expected_abi: $version_name" >&2
        exit 1
    fi
    if [[ -z "$signer" || "$signer" != "$standard_signer" ]]; then
        echo "Smart APK signer mismatch for $expected_abi" >&2
        exit 1
    fi
    if [[ "$abis" != "$expected_abi" ]]; then
        echo "Unexpected smart APK ABI for $apk: $abis (expected $expected_abi)" >&2
        exit 1
    fi
    if [[ "$label" != "$EXPECTED_LABEL" ]]; then
        echo "Unexpected smart application label for $expected_abi: $label" >&2
        exit 1
    fi
    mapfile -t smart_models < <(
        unzip -Z1 "$apk" |
            grep '^assets/smart_replacement/.*\.ort$' || true
    )
    if [[ "${#smart_models[@]}" -ne 1 ]]; then
        echo "Expected exactly one ORT model in smart $expected_abi APK, found ${#smart_models[@]}" >&2
        printf '%s\n' "${smart_models[@]}" >&2
        exit 1
    fi
    if [[ "${smart_models[0]}" != "$EXPECTED_MODEL_PATH" ]]; then
        echo "Unexpected smart replacement model path: ${smart_models[0]}" >&2
        exit 1
    fi
    model_method="$(unzip -lv "$apk" "${smart_models[0]}" | awk -v target="${smart_models[0]}" '$NF == target { print $2 }')"
    if [[ "$model_method" != "Stored" ]]; then
        echo "Smart APK ORT model must be stored without compression: ${smart_models[0]} ($model_method)" >&2
        exit 1
    fi
    model_sha256="$(unzip -p "$apk" "$EXPECTED_MODEL_PATH" | sha256sum | awk '{ print $1 }')"
    vocab_sha256="$(unzip -p "$apk" "$EXPECTED_VOCAB_PATH" | sha256sum | awk '{ print $1 }')"
    if [[ "$model_sha256" != "$EXPECTED_MODEL_SHA256" ]]; then
        echo "Unexpected smart replacement model SHA-256: $model_sha256" >&2
        exit 1
    fi
    if [[ "$vocab_sha256" != "$EXPECTED_VOCAB_SHA256" ]]; then
        echo "Unexpected smart replacement vocabulary SHA-256: $vocab_sha256" >&2
        exit 1
    fi
    if ! unzip -Z1 "$apk" | grep "^lib/${expected_abi}/libonnxruntime.*\\.so$" > /dev/null; then
        echo "Smart $expected_abi APK is missing ONNX Runtime native library" >&2
        exit 1
    fi
    smart_size="$(stat -c '%s' "$apk")"
    smart_delta=$((smart_size - standard_size))
    if (( smart_delta > MAX_SMART_APK_DELTA_BYTES )); then
        echo "Smart $expected_abi APK exceeds the 80 MiB size delta: $smart_delta bytes" >&2
        exit 1
    fi
    if (( smart_delta > WARN_SMART_APK_DELTA_BYTES )); then
        echo "Warning: Smart $expected_abi APK exceeds the 50 MiB target delta: $smart_delta bytes" >&2
    fi
}

standard_badging="$(apk_badging "$STANDARD_APK")"
standard_package="$(badging_value "$standard_badging" name)"
standard_version_code="$(badging_value "$standard_badging" versionCode)"
standard_version_name="$(badging_value "$standard_badging" versionName)"
standard_signer="$(apk_signer "$STANDARD_APK")"
standard_abis="$(apk_abis "$STANDARD_APK")"
standard_label="$(apk_application_label "$STANDARD_APK")"
standard_size="$(stat -c '%s' "$STANDARD_APK")"

if [[ "$standard_package" != "$EXPECTED_PACKAGE" ]]; then
    echo "APK package mismatch: standard=$standard_package" >&2
    exit 1
fi
if [[ -z "$standard_version_code" ]]; then
    echo "Standard APK is missing versionCode" >&2
    exit 1
fi
if [[ -z "$standard_version_name" ]]; then
    echo "Standard APK is missing versionName" >&2
    exit 1
fi
if [[ -z "$standard_signer" ]]; then
    echo "Standard APK signer is missing" >&2
    exit 1
fi
if [[ "$standard_abis" != "$EXPECTED_STANDARD_ABIS" ]]; then
    echo "Unexpected standard APK ABIs: $standard_abis" >&2
    exit 1
fi
if [[ "$standard_label" != "$EXPECTED_LABEL" ]]; then
    echo "Unexpected standard application label: $standard_label" >&2
    exit 1
fi
if unzip -Z1 "$STANDARD_APK" | grep '^assets/smart_replacement/' > /dev/null; then
    echo "Standard APK contains smart replacement assets" >&2
    exit 1
fi
if unzip -Z1 "$STANDARD_APK" | grep '^lib/[^/]*/libonnxruntime.*\.so$' > /dev/null; then
    echo "Standard APK contains ONNX Runtime native libraries" >&2
    exit 1
fi

verify_smart_apk \
    "$SMART_ARM64_APK" \
    "arm64-v8a" \
    "$standard_package" \
    "$standard_version_code" \
    "$standard_version_name" \
    "$standard_signer" \
    "$standard_size"
verify_smart_apk \
    "$SMART_X86_64_APK" \
    "x86_64" \
    "$standard_package" \
    "$standard_version_code" \
    "$standard_version_name" \
    "$standard_signer" \
    "$standard_size"

printf 'Verified standard universal and per-ABI smart APKs: package=%s versionCode=%s\n' \
    "$standard_package" \
    "$standard_version_code"
