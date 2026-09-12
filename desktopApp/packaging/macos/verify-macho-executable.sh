#!/usr/bin/env bash
set -euo pipefail

binary="${1:?usage: verify-macho-executable.sh <packaged-native-image-binary>}"
expected_rpath='@executable_path/.'

if [[ ! -f "$binary" ]]; then
  echo "Packaged Mach-O verification: executable not found: $binary" >&2
  exit 1
fi

echo "Packaged Mach-O verification: $binary"
file "$binary"
load_commands="$(xcrun otool -l "$binary")"

rpath_count="$(printf '%s\n' "$load_commands" | awk -v expected="$expected_rpath" '
  $1 == "cmd" && $2 == "LC_RPATH" { in_rpath=1; next }
  in_rpath && $1 == "path" {
    if ($2 == expected) count++
    in_rpath=0
  }
  END { print count+0 }
')"

if [[ "$rpath_count" -ne 1 ]]; then
  echo "::error::Expected exactly one LC_RPATH '$expected_rpath' in $binary, found $rpath_count" >&2
  exit 1
fi

echo "Packaged Mach-O verification: LC_BUILD_VERSION"
printf '%s\n' "$load_commands" | awk '
  $1 == "cmd" && $2 == "LC_BUILD_VERSION" { active=1; print "  cmd LC_BUILD_VERSION"; next }
  active && ($1 == "platform" || $1 == "minos" || $1 == "sdk" || $1 == "ntools") {
    print "  " $0
    if ($1 == "ntools") exit
  }
'

echo "Packaged Mach-O verification: required LC_RPATH present exactly once"
