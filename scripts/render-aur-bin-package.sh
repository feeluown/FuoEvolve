#!/usr/bin/env bash
set -euo pipefail

tag="${1:?release tag is required}"
output_dir="${2:?output directory is required}"

if [[ ! "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Unsupported AUR release tag: $tag" >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
template_dir="$repo_root/desktopApp/packaging/aur-bin"
asset_name="FuoEvolve-${tag}-linux-x64.pkg.tar.zst"
release_api="https://api.github.com/repos/feeluown/FuoEvolve/releases/tags/${tag}"

curl_args=(--fail --silent --show-error --location --retry 3 --retry-all-errors
  -H 'Accept: application/vnd.github+json')
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  curl_args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

# GitHub calculates the uploaded asset digest; use that exact binary checksum
# instead of hashing the source archive or depending on the build job's output.
release_metadata="$(curl "${curl_args[@]}" "$release_api")"
asset_digest="$(jq -er --arg name "$asset_name" \
  '[.assets[] | select(.name == $name) | .digest] | if length == 1 then .[0] else error("Expected exactly one Arch release asset") end' \
  <<< "$release_metadata")"
if [[ ! "$asset_digest" =~ ^sha256:([0-9a-fA-F]{64})$ ]]; then
  echo "Missing or invalid SHA-256 digest for release asset $asset_name: $asset_digest" >&2
  exit 1
fi
binary_sha256="${BASH_REMATCH[1],,}"

mkdir -p "$output_dir"
render_template() {
  local source_file="$1"
  local target_file="$2"
  sed \
    -e "s/@PKGVER@/${tag}/g" \
    -e "s/@BINARY_SHA256@/${binary_sha256}/g" \
    "$source_file" > "$target_file"
}

render_template "$template_dir/PKGBUILD.template" "$output_dir/PKGBUILD"
render_template "$template_dir/SRCINFO.template" "$output_dir/.SRCINFO"

bash -n "$output_dir/PKGBUILD"
if grep -E '@[A-Z_][A-Z_]*@' "$output_dir/PKGBUILD" "$output_dir/.SRCINFO"; then
  echo "AUR binary package rendering left unresolved placeholders" >&2
  exit 1
fi
if ! grep -Fq 'releases/download/${pkgver}/${_archive}' "$output_dir/PKGBUILD" || \
   ! grep -Fq "releases/download/${tag}/${asset_name}" "$output_dir/.SRCINFO"; then
  echo "AUR binary package must consume the corresponding Arch release asset" >&2
  exit 1
fi

echo "Rendered AUR binary package for ${tag} from ${asset_name}"
echo "Binary SHA-256: ${binary_sha256}"
