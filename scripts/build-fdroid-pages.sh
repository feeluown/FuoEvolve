#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly WORK_DIR="${1:-$PROJECT_ROOT/build/fdroid}"
readonly PAGES_DIR="${2:-$PROJECT_ROOT/build/pages}"
readonly RELEASE_LIMIT="${FDROID_RELEASE_LIMIT:-5}"
readonly REPOSITORY="${GITHUB_REPOSITORY:-feeluown/FuoEvolve}"
readonly PACKAGE_NAME="org.feeluown.mobile"
readonly EXPECTED_SIGNER_SHA256="${FUO_APK_SIGNER_SHA256:-8d8be45a04cf3242c13b43361c9ffa1ca8fb2f39d1a43ce35beadfa8dbfefb74}"
ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_SDK_ROOT" && -f "$PROJECT_ROOT/local.properties" ]]; then
    ANDROID_SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | tail -n 1)"
fi
readonly ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT

for name in FUO_SIGNING_KEYSTORE_BASE64 FUO_SIGNING_STORE_PASSWORD FUO_SIGNING_KEY_ALIAS FUO_SIGNING_KEY_PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required F-Droid signing secret: $name" >&2
        exit 1
    fi
done

if [[ ! "$RELEASE_LIMIT" =~ ^[1-9][0-9]*$ ]]; then
    echo "FDROID_RELEASE_LIMIT must be a positive integer" >&2
    exit 1
fi

case "$WORK_DIR" in
    "$PROJECT_ROOT"/build/*) ;;
    *) echo "Work directory must be inside $PROJECT_ROOT/build" >&2; exit 1 ;;
esac
case "$PAGES_DIR" in
    "$PROJECT_ROOT"/build/*) ;;
    *) echo "Pages directory must be inside $PROJECT_ROOT/build" >&2; exit 1 ;;
esac

find_android_tool() {
    local tool_name="$1"
    local resolved

    if resolved="$(command -v "$tool_name" 2>/dev/null)"; then
        printf '%s\n' "$resolved"
        return
    fi

    if [[ -z "$ANDROID_SDK_ROOT" ]]; then
        echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
        return 1
    fi

    resolved="$(find "$ANDROID_SDK_ROOT/build-tools" -type f -name "$tool_name" -print | sort -V | tail -n 1)"
    if [[ -z "$resolved" ]]; then
        echo "Unable to find Android tool: $tool_name" >&2
        return 1
    fi
    printf '%s\n' "$resolved"
}

AAPT="$(find_android_tool aapt)" || exit 1
APKSIGNER="$(find_android_tool apksigner)" || exit 1
readonly AAPT APKSIGNER

rm -rf "$WORK_DIR" "$PAGES_DIR"
mkdir -p "$WORK_DIR/repo" "$WORK_DIR/metadata" "$PAGES_DIR"

cp "$PROJECT_ROOT/fdroid/config.yml.template" "$WORK_DIR/config.yml"
cp -a "$PROJECT_ROOT/fdroid/config" "$WORK_DIR/config"
cp "$PROJECT_ROOT/fdroid/metadata/org.feeluown.mobile.yml" "$WORK_DIR/metadata/"
cp "$PROJECT_ROOT/androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png" "$WORK_DIR/icon.png"
printf '%s' "$FUO_SIGNING_KEYSTORE_BASE64" | base64 --decode > "$WORK_DIR/fuo-evolve.jks"
chmod 0600 "$WORK_DIR/config.yml" "$WORK_DIR/fuo-evolve.jks"

mapfile -t release_tags < <(
    gh release list \
        --repo "$REPOSITORY" \
        --exclude-drafts \
        --exclude-pre-releases \
        --limit 100 \
        --json tagName,publishedAt \
        --jq 'sort_by(.publishedAt) | reverse | .[].tagName'
)

downloaded=0
for tag in "${release_tags[@]}"; do
    asset_name="$(
        gh release view "$tag" \
            --repo "$REPOSITORY" \
            --json assets \
            --jq '[.assets[].name | select(endswith("-arm64-v8a-signed.apk"))][0] // ""'
    )"
    if [[ -z "$asset_name" ]]; then
        echo "Skipping $tag: no arm64-v8a signed APK"
        continue
    fi

    gh release download "$tag" \
        --repo "$REPOSITORY" \
        --pattern "$asset_name" \
        --dir "$WORK_DIR/repo"
    downloaded=$((downloaded + 1))
    if (( downloaded >= RELEASE_LIMIT )); then
        break
    fi
done

if (( downloaded == 0 )); then
    echo "No arm64-v8a release APKs were downloaded" >&2
    exit 1
fi

declare -A version_codes=()
for apk in "$WORK_DIR"/repo/*.apk; do
    badging="$($AAPT dump badging "$apk")"
    actual_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
    version_code="$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<< "$badging")"
    signer="$($APKSIGNER verify --print-certs "$apk" | sed -n 's/^.*certificate SHA-256 digest: //p' | sort -u | tr '[:upper:]' '[:lower:]')"

    if [[ "$actual_package" != "$PACKAGE_NAME" ]]; then
        echo "Unexpected package in $apk: $actual_package" >&2
        exit 1
    fi
    if [[ "$signer" != "$EXPECTED_SIGNER_SHA256" ]]; then
        echo "Unexpected APK signer in $apk: $signer" >&2
        exit 1
    fi
    if [[ -n "${version_codes[$version_code]:-}" ]]; then
        echo "Duplicate versionCode $version_code in $apk and ${version_codes[$version_code]}" >&2
        exit 1
    fi
    version_codes[$version_code]="$apk"
done

(
    cd "$WORK_DIR"
    fdroid lint "$PACKAGE_NAME"
    fdroid update --verbose
)

cp -a "$PROJECT_ROOT/docs/." "$PAGES_DIR/"
mkdir -p "$PAGES_DIR/fdroid"
cp -a "$WORK_DIR/repo" "$PAGES_DIR/fdroid/repo"
touch "$PAGES_DIR/.nojekyll"

test -f "$PAGES_DIR/404.html"
test -f "$PAGES_DIR/fdroid/repo/index-v2.json"
test -f "$PAGES_DIR/fdroid/repo/entry.json"
