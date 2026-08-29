#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 <stable|canary> <apk> <output-json> <apk-url>" >&2
    exit 2
fi

readonly CHANNEL="$1"
readonly APK_PATH="$2"
readonly OUTPUT_PATH="$3"
readonly APK_URL="$4"
readonly PACKAGE_NAME="org.feeluown.mobile"

case "$CHANNEL" in
    stable|canary) ;;
    *) echo "Unsupported update channel: $CHANNEL" >&2; exit 2 ;;
esac

if [[ ! -f "$APK_PATH" ]]; then
    echo "APK does not exist: $APK_PATH" >&2
    exit 1
fi

ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
    exit 1
fi

find_android_tool() {
    local tool_name="$1"
    local resolved

    if resolved="$(command -v "$tool_name" 2>/dev/null)"; then
        printf '%s\n' "$resolved"
        return
    fi

    resolved="$(find "$ANDROID_SDK_ROOT/build-tools" -type f -name "$tool_name" -print | sort -V | tail -n 1)"
    if [[ -z "$resolved" ]]; then
        echo "Unable to find Android tool: $tool_name" >&2
        return 1
    fi
    printf '%s\n' "$resolved"
}

AAPT="$(find_android_tool aapt)" || exit 1
readonly AAPT

badging="$($AAPT dump badging "$APK_PATH")"
actual_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
version_code="$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<< "$badging")"
version_name="$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<< "$badging")"

if [[ "$actual_package" != "$PACKAGE_NAME" ]]; then
    echo "Unexpected package in $APK_PATH: $actual_package" >&2
    exit 1
fi
if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid APK versionCode: $version_code" >&2
    exit 1
fi
if [[ -z "$version_name" ]]; then
    echo "Missing APK versionName" >&2
    exit 1
fi

sha256="$(sha256sum "$APK_PATH" | awk '{print $1}')"
size="$(stat -c '%s' "$APK_PATH")"
published_at="${PUBLISHED_AT:-$(date -u +'%Y-%m-%dT%H:%M:%SZ')}"
mkdir -p "$(dirname "$OUTPUT_PATH")"

MANIFEST_CHANNEL="$CHANNEL" \
MANIFEST_VERSION_CODE="$version_code" \
MANIFEST_VERSION_NAME="$version_name" \
MANIFEST_APK_URL="$APK_URL" \
MANIFEST_APK_SHA256="$sha256" \
MANIFEST_APK_SIZE="$size" \
MANIFEST_PUBLISHED_AT="$published_at" \
MANIFEST_COMMIT_SHA="${COMMIT_SHA:-}" \
MANIFEST_WORKFLOW_RUN_ID="${WORKFLOW_RUN_ID:-}" \
MANIFEST_RELEASE_NOTES_URL="${RELEASE_NOTES_URL:-}" \
python3 - "$OUTPUT_PATH" <<'PY'
import json
import os
import sys

output = sys.argv[1]
payload = {
    "schemaVersion": 1,
    "channel": os.environ["MANIFEST_CHANNEL"],
    "versionCode": int(os.environ["MANIFEST_VERSION_CODE"]),
    "versionName": os.environ["MANIFEST_VERSION_NAME"],
    "publishedAt": os.environ["MANIFEST_PUBLISHED_AT"],
    "apk": {
        "url": os.environ["MANIFEST_APK_URL"],
        "sha256": os.environ["MANIFEST_APK_SHA256"],
        "size": int(os.environ["MANIFEST_APK_SIZE"]),
    },
}
commit_sha = os.environ.get("MANIFEST_COMMIT_SHA")
if commit_sha:
    payload["commitSha"] = commit_sha
workflow_run_id = os.environ.get("MANIFEST_WORKFLOW_RUN_ID")
if workflow_run_id:
    payload["workflowRunId"] = int(workflow_run_id)
release_notes_url = os.environ.get("MANIFEST_RELEASE_NOTES_URL")
if release_notes_url:
    payload["releaseNotesUrl"] = release_notes_url

with open(output, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY

printf 'Generated %s update manifest: %s (versionCode=%s, versionName=%s)\n' \
    "$CHANNEL" "$OUTPUT_PATH" "$version_code" "$version_name"
