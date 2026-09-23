#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: $0 <file-or-directory> [...]" >&2
  exit 2
fi

for command in file find grep readelf; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required to verify the Linux x86-64 ISA baseline" >&2
    exit 1
  }
done

scanned=0
violations=0

check_elf() {
  local candidate="$1"
  local description notes required

  description="$(file -Lb "$candidate" 2>/dev/null || true)"
  [[ "$description" == ELF* ]] || return 0

  scanned=$((scanned + 1))
  notes="$(readelf --notes "$candidate" 2>/dev/null || true)"
  required="$(grep -E 'x86 ISA needed:' <<<"$notes" || true)"
  if grep -Eq 'x86 ISA needed:.*x86-64-v[234]' <<<"$required"; then
    echo "Linux package requires an unsupported x86-64 ISA level: $candidate" >&2
    echo "$required" >&2
    violations=$((violations + 1))
  fi
}

for root in "$@"; do
  if [[ -f "$root" ]]; then
    check_elf "$root"
  elif [[ -d "$root" ]]; then
    while IFS= read -r -d '' candidate; do
      check_elf "$candidate"
    done < <(find "$root" -type f -print0)
  else
    echo "ISA verification input does not exist: $root" >&2
    exit 1
  fi
done

if [[ "$violations" -ne 0 ]]; then
  echo "Found $violations ELF file(s) requiring x86-64-v2 or newer." >&2
  exit 1
fi

echo "Verified $scanned ELF file(s): no x86-64 ISA requirement above baseline."
