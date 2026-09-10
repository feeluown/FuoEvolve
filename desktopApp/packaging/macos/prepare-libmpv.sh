#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <output-dir>" >&2
  exit 2
fi

OUTPUT_DIR="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK_FILE="$SCRIPT_DIR/../native-deps.lock"
export HOMEBREW_NO_AUTO_UPDATE=1

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required to prepare the macOS libmpv bundle" >&2
  exit 1
fi
if [[ ! -f "$LOCK_FILE" ]]; then
  echo "Native dependency lock file is missing: $LOCK_FILE" >&2
  exit 1
fi

case "$(uname -m)" in
  arm64) MPV_VERSION_KEY="macos.arm64.mpv.version" ;;
  x86_64) MPV_VERSION_KEY="macos.x64.mpv.version" ;;
  *)
    echo "Unsupported macOS packaging architecture: $(uname -m)" >&2
    exit 1
    ;;
esac
PINNED_MPV_VERSION="$(awk -F= -v key="$MPV_VERSION_KEY" '$1 == key { print $2; exit }' "$LOCK_FILE")"
if [[ -z "$PINNED_MPV_VERSION" ]]; then
  echo "$MPV_VERSION_KEY is missing from $LOCK_FILE" >&2
  exit 1
fi

brew list mpv >/dev/null 2>&1 || brew install mpv
brew list dylibbundler >/dev/null 2>&1 || brew install dylibbundler

INSTALLED_MPV_VERSION="$(brew list --versions mpv | awk '{ print $2; exit }')"
if [[ "$INSTALLED_MPV_VERSION" != "$PINNED_MPV_VERSION" ]]; then
  echo "Homebrew resolved mpv $INSTALLED_MPV_VERSION for $(uname -m) but packaging is pinned to $PINNED_MPV_VERSION in $LOCK_FILE" >&2
  echo "Update the architecture-specific packaging lock deliberately before shipping a different macOS libmpv runtime." >&2
  exit 1
fi

MPV_PREFIX="$(brew --prefix mpv)"
SOURCE_LIB="$MPV_PREFIX/lib/libmpv.dylib"
SOURCE_HEADERS="$MPV_PREFIX/include/mpv"
if [[ ! -f "$SOURCE_LIB" ]]; then
  echo "Homebrew mpv did not provide $SOURCE_LIB" >&2
  exit 1
fi
if [[ ! -f "$SOURCE_HEADERS/client.h" ]]; then
  echo "Homebrew mpv did not provide development headers below $SOURCE_HEADERS" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
WORKING_LIB="$WORK_DIR/libmpv.dylib"
cp -L "$SOURCE_LIB" "$WORKING_LIB"

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/include"

# Keep the development headers with the relocatable runtime so CI can cache one immutable bundle
# and compile the JNI bridge without consulting Homebrew on cache hits.
cp -R "$SOURCE_HEADERS" "$OUTPUT_DIR/include/mpv"

# dylibbundler clears its dependency output directory before copying dependencies. Its output must
# therefore go to a temporary sibling directory; merge the collected dylibs into OUTPUT_DIR after
# the closure is complete so the cached include/ tree is preserved.
BUNDLED_LIB_DIR="$WORK_DIR/bundled-libs"
mkdir -p "$BUNDLED_LIB_DIR"
dylibbundler \
  -od \
  -b \
  -x "$WORKING_LIB" \
  -d "$BUNDLED_LIB_DIR" \
  -p "@loader_path/"
install_name_tool -id "@loader_path/libmpv.dylib" "$WORKING_LIB"
find "$BUNDLED_LIB_DIR" -maxdepth 1 -type f -name '*.dylib' -exec cp -p {} "$OUTPUT_DIR/" \;
mv "$WORKING_LIB" "$OUTPUT_DIR/libmpv.dylib"

# A relocatable bundle must not retain references to the Homebrew prefix/Cellar.
while IFS= read -r dylib; do
  if otool -L "$dylib" | tail -n +2 | grep -E '/(opt/homebrew|usr/local)/(Cellar|opt)/' >/dev/null; then
    echo "Non-relocatable Homebrew dependency remains in $dylib:" >&2
    otool -L "$dylib" >&2
    exit 1
  fi
done < <(find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.dylib' -print)

{
  echo "Source: Homebrew mpv $INSTALLED_MPV_VERSION"
  echo "Pinned version key: $MPV_VERSION_KEY"
  echo "Pinned version: $PINNED_MPV_VERSION"
  echo "Homebrew prefix: $MPV_PREFIX"
  echo "Purpose: cached development headers + bundled relocatable libmpv runtime for FuoEvolve macOS desktop"
} > "$OUTPUT_DIR/FUOEVOLVE_LIBMPV_SOURCE.txt"

printf 'Prepared macOS libmpv bundle (%s) at %s\n' "$(uname -m)" "$OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"
