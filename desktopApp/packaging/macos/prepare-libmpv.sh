#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <output-dir>" >&2
  exit 2
fi

OUTPUT_DIR="$1"
export HOMEBREW_NO_AUTO_UPDATE=1

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required to prepare the macOS libmpv bundle" >&2
  exit 1
fi

brew list mpv >/dev/null 2>&1 || brew install mpv
brew list dylibbundler >/dev/null 2>&1 || brew install dylibbundler

MPV_PREFIX="$(brew --prefix mpv)"
SOURCE_LIB="$MPV_PREFIX/lib/libmpv.dylib"
if [[ ! -f "$SOURCE_LIB" ]]; then
  echo "Homebrew mpv did not provide $SOURCE_LIB" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
WORKING_LIB="$WORK_DIR/libmpv.dylib"
cp -L "$SOURCE_LIB" "$WORKING_LIB"

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

# dylibbundler clears its dependency output directory before copying dependencies. Keep the root
# libmpv dylib in a separate working directory while it is rewritten, then move it next to the
# collected dylibs once the dependency closure is complete.
dylibbundler \
  -od \
  -b \
  -x "$WORKING_LIB" \
  -d "$OUTPUT_DIR" \
  -p "@loader_path/"
install_name_tool -id "@loader_path/libmpv.dylib" "$WORKING_LIB"
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
  echo "Source: Homebrew mpv $(brew list --versions mpv | head -n 1)"
  echo "Homebrew prefix: $MPV_PREFIX"
  echo "Purpose: bundled relocatable libmpv runtime for the FuoEvolve macOS desktop package"
} > "$OUTPUT_DIR/FUOEVOLVE_LIBMPV_SOURCE.txt"

printf 'Prepared macOS libmpv bundle (%s) at %s\n' "$(uname -m)" "$OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"
