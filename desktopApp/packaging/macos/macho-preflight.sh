#!/usr/bin/env bash
set -euo pipefail

binary="${1:?usage: macho-preflight.sh <native-image-binary>}"

if [[ ! -f "$binary" ]]; then
  echo "Mach-O preflight: native image binary not found: $binary" >&2
  exit 1
fi

echo "Mach-O preflight: $binary"
file "$binary"
echo "DEVELOPER_DIR=${DEVELOPER_DIR:-<unset>}"
echo "xcode-select=$(xcode-select -p)"
echo "strip=$(xcrun --find strip)"
echo "install_name_tool=$(xcrun --find install_name_tool)"

# The selected GraalVM/Xcode pair must produce a Mach-O that Apple's packaging tools can safely
# mutate. This intentionally fails early now that macOS Native Image is pinned to the compatible
# LTS toolchain instead of deferring a broken executable to the packaging phase.
xcrun otool -l "$binary" >/dev/null

echo "Mach-O preflight: raw LC_BUILD_VERSION"
xcrun otool -l "$binary" | awk '
  $1 == "cmd" && $2 == "LC_BUILD_VERSION" { active=1; print "  cmd LC_BUILD_VERSION"; next }
  active && ($1 == "platform" || $1 == "minos" || $1 == "sdk" || $1 == "ntools") {
    print "  " $0
    if ($1 == "ntools") exit
  }
'

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/fuoevolve-macho-preflight.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

strip_copy="$tmpdir/fuoevolve-strip"
cp -p "$binary" "$strip_copy"
if ! xcrun strip -x "$strip_copy"; then
  echo "::error::Mach-O preflight: raw native-image binary rejects strip -x" >&2
  exit 1
fi
echo "Mach-O preflight: raw native-image binary accepts strip -x"

rpath_copy="$tmpdir/fuoevolve-rpath"
cp -p "$binary" "$rpath_copy"
if ! xcrun install_name_tool -add_rpath '@executable_path/.' "$rpath_copy"; then
  echo "::error::Mach-O preflight: raw native-image binary rejects install_name_tool -add_rpath" >&2
  exit 1
fi
xcrun otool -l "$rpath_copy" >/dev/null
echo "Mach-O preflight: raw native-image binary accepts install_name_tool -add_rpath"
