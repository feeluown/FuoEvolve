#!/usr/bin/env bash
set -euo pipefail

tag="${1:?release tag is required}"
commit_sha="${2:?release commit SHA is required}"
output_dir="${3:?output directory is required}"

if [[ ! "$tag" =~ ^[0-9]+(\.[0-9]+){2,3}$ ]]; then
  echo "Unsupported AUR release tag: $tag" >&2
  exit 1
fi
if [[ ! "$commit_sha" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Invalid release commit SHA: $commit_sha" >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
template_dir="$repo_root/desktopApp/packaging/aur"
source_url="https://github.com/feeluown/FuoEvolve/archive/refs/tags/${tag}.tar.gz"
source_archive="$(mktemp)"
trap 'rm -f "$source_archive"' EXIT

curl --fail --location --retry 3 --retry-all-errors "$source_url" --output "$source_archive"
source_sha256="$(sha256sum "$source_archive" | awk '{print $1}')"

mkdir -p "$output_dir"

render_template() {
  local source_file="$1"
  local target_file="$2"
  sed \
    -e "s/@PKGVER@/${tag}/g" \
    -e "s/@SOURCE_SHA256@/${source_sha256}/g" \
    -e "s/@COMMIT_SHA@/${commit_sha}/g" \
    "$source_file" > "$target_file"
}

render_template "$template_dir/PKGBUILD.template" "$output_dir/PKGBUILD"
render_template "$template_dir/SRCINFO.template" "$output_dir/.SRCINFO"

bash -n "$output_dir/PKGBUILD"
if grep -R '@[A-Z_][A-Z_]*@' "$output_dir/PKGBUILD" "$output_dir/.SRCINFO"; then
  echo "AUR package rendering left unresolved placeholders" >&2
  exit 1
fi
if grep -Eq 'releases/download/.*(pkg\.tar|\.pacman)|FuoEvolve-.*-linux-x64\.pkg\.tar' \
  "$output_dir/PKGBUILD" "$output_dir/.SRCINFO"; then
  echo "AUR PKGBUILD must build from source, not consume a release binary package" >&2
  exit 1
fi

echo "Rendered AUR package for ${tag} from ${source_url}"
echo "Source SHA-256: ${source_sha256}"
