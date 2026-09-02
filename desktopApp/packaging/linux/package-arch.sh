#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <compose-app-image> <version> <output-dir>" >&2
  exit 2
fi

APP_IMAGE="$(realpath "$1")"
VERSION="$2"
OUTPUT_DIR="$(mkdir -p "$3" && realpath "$3")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ICON="$REPO_ROOT/androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png"

if [[ ! -d "$APP_IMAGE" || ! -x "$APP_IMAGE/bin/FuoEvolve" ]]; then
  echo "invalid Compose app image: $APP_IMAGE" >&2
  exit 1
fi
if [[ ! -f "$ICON" ]]; then
  echo "desktop package icon is missing: $ICON" >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to build the Arch package in a clean Arch environment" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

cp -a "$APP_IMAGE" "$WORK_DIR/FuoEvolve"
tar -C "$WORK_DIR" -czf "$WORK_DIR/FuoEvolve.tar.gz" FuoEvolve
rm -rf "$WORK_DIR/FuoEvolve"
cp "$ICON" "$WORK_DIR/fuoevolve.png"
cat > "$WORK_DIR/fuoevolve.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=FuoEvolve
Comment=A cross-platform multi-source music player based on FeelUOwn
Exec=/opt/fuoevolve/bin/FuoEvolve %U
Icon=fuoevolve
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupNotify=true
DESKTOP

APP_SHA="$(sha256sum "$WORK_DIR/FuoEvolve.tar.gz" | awk '{print $1}')"
DESKTOP_SHA="$(sha256sum "$WORK_DIR/fuoevolve.desktop" | awk '{print $1}')"
ICON_SHA="$(sha256sum "$WORK_DIR/fuoevolve.png" | awk '{print $1}')"

cat > "$WORK_DIR/PKGBUILD" <<EOF
pkgname=fuoevolve
pkgver=$VERSION
pkgrel=1
pkgdesc='A cross-platform multi-source music player based on FeelUOwn'
arch=('x86_64')
url='https://github.com/feeluown/FuoEvolve'
license=('GPL-3.0-only')
depends=('mpv' 'libsecret')
options=('!strip')
source=('FuoEvolve.tar.gz' 'fuoevolve.desktop' 'fuoevolve.png')
sha256sums=('$APP_SHA' '$DESKTOP_SHA' '$ICON_SHA')

package() {
  install -d "\$pkgdir/opt/fuoevolve"
  cp -a "\$srcdir/FuoEvolve/." "\$pkgdir/opt/fuoevolve/"
  install -Dm644 "\$srcdir/fuoevolve.desktop" "\$pkgdir/usr/share/applications/fuoevolve.desktop"
  install -Dm644 "\$srcdir/fuoevolve.png" "\$pkgdir/usr/share/icons/hicolor/192x192/apps/fuoevolve.png"
  install -d "\$pkgdir/usr/bin"
  ln -s /opt/fuoevolve/bin/FuoEvolve "\$pkgdir/usr/bin/fuoevolve"
}
EOF

# makepkg intentionally runs as a non-root user; the package itself retains distro-managed
# dependencies on mpv/libsecret while the Compose app image keeps its bundled JBR runtime.
# The bind-mounted work directory must be returned to the host runner's numeric ownership before
# Docker exits, otherwise chown-ing it to the container-only builder user makes host cleanup fail.
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"
docker run --rm \
  -e HOST_UID="$HOST_UID" \
  -e HOST_GID="$HOST_GID" \
  -v "$WORK_DIR:/work" \
  archlinux:latest bash -lc '
  set -e
  restore_host_ownership() {
    chown -R "$HOST_UID:$HOST_GID" /work || true
  }
  trap restore_host_ownership EXIT
  pacman -Syu --noconfirm --needed base-devel namcap
  useradd -m builder
  chown -R builder:builder /work
  su builder -c "cd /work && makepkg --nodeps --noconfirm --cleanbuild"
  namcap /work/*.pkg.tar.zst || true
  chmod a+r /work/*.pkg.tar.zst
'

PACKAGE_FILE="$(find "$WORK_DIR" -maxdepth 1 -type f -name 'fuoevolve-*.pkg.tar.zst' -print -quit)"
if [[ -z "$PACKAGE_FILE" ]]; then
  echo "Arch package was not produced" >&2
  exit 1
fi
cp "$PACKAGE_FILE" "$OUTPUT_DIR/"
printf 'Built Arch package: %s\n' "$OUTPUT_DIR/$(basename "$PACKAGE_FILE")"
