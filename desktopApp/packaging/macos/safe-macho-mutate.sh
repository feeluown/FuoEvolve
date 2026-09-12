#!/usr/bin/env bash
set -euo pipefail

operation="${1:?usage: safe-macho-mutate.sh <strip|rpath> <binary>}"
binary="${2:?usage: safe-macho-mutate.sh <strip|rpath> <binary>}"

if [[ ! -f "$binary" ]]; then
  echo "Safe Mach-O mutation: binary not found: $binary" >&2
  exit 1
fi

dir="$(dirname "$binary")"
base="$(basename "$binary")"
tmp="$(mktemp "$dir/.${base}.fuoevolve-macho.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
cp -p "$binary" "$tmp"

# The bundle is ad-hoc signed later. Removing an existing signature from the temporary copy keeps
# Apple's mutation tools from preserving stale signature data if an upstream task starts signing earlier.
xcrun codesign --remove-signature "$tmp" >/dev/null 2>&1 || true

case "$operation" in
  strip)
    command=(xcrun strip -x "$tmp")
    ;;
  rpath)
    command=(xcrun install_name_tool -add_rpath '@executable_path/.' "$tmp")
    ;;
  *)
    echo "Safe Mach-O mutation: unsupported operation: $operation" >&2
    exit 2
    ;;
esac

if ! "${command[@]}"; then
  echo "::error::Safe Mach-O $operation rejected for $base; original executable kept" >&2
  exit 1
fi

if ! xcrun otool -l "$tmp" >/dev/null; then
  echo "::error::Safe Mach-O $operation produced an unreadable file for $base; original executable kept" >&2
  exit 1
fi

mv -f "$tmp" "$binary"
trap - EXIT
echo "Safe Mach-O $operation applied to $base"
