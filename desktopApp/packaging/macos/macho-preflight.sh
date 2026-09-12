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

# A malformed Mach-O should still fail fast. The mutation probes below are diagnostic only:
# packaging can keep the original binary when Apple's rewriting tools reject GraalVM's layout.
xcrun otool -l "$binary" >/dev/null

tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/fuoevolve-macho-preflight.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

strip_copy="$tmpdir/fuoevolve-strip"
cp -p "$binary" "$strip_copy"
if xcrun strip -x "$strip_copy"; then
  echo "Mach-O preflight: raw native-image binary accepts strip -x"
else
  echo "::warning::Mach-O preflight: raw native-image binary rejects strip -x; packaging will keep the original executable"
fi

rpath_copy="$tmpdir/fuoevolve-rpath"
cp -p "$binary" "$rpath_copy"
if xcrun install_name_tool -add_rpath '@executable_path/.' "$rpath_copy"; then
  echo "Mach-O preflight: raw native-image binary accepts install_name_tool -add_rpath"
else
  echo "::warning::Mach-O preflight: raw native-image binary rejects install_name_tool; packaging will keep the original executable"
fi
