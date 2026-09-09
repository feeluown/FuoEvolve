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
if [[ ! -d "$APP_IMAGE/lib/app" ]] || ! find "$APP_IMAGE/lib/app" -maxdepth 1 -type f -name '*.jar' -print -quit | grep -q .; then
  echo "Compose app image is missing application JARs: $APP_IMAGE/lib/app" >&2
  exit 1
fi
if [[ ! -f "$ICON" ]]; then
  echo "desktop package icon is missing: $ICON" >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to build the Arch packages in a clean Arch environment" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
BUNDLED_DIR="$WORK_DIR/bundled"
SYSTEM_DIR="$WORK_DIR/system-jvm"
mkdir -p "$BUNDLED_DIR" "$SYSTEM_DIR"

write_common_files() {
  local target_dir="$1"
  cp "$ICON" "$target_dir/fuoevolve.png"
  cat > "$target_dir/fuoevolve.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=FuoEvolve
Comment=A cross-platform multi-source music player based on FeelUOwn
Exec=/usr/bin/fuoevolve %U
Icon=fuoevolve
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupNotify=true
DESKTOP
}

write_common_files "$BUNDLED_DIR"
write_common_files "$SYSTEM_DIR"

# Default Arch package: keep the Compose-generated launcher and its bundled JBR runtime.
cp -a "$APP_IMAGE" "$BUNDLED_DIR/FuoEvolve"
tar -C "$BUNDLED_DIR" -czf "$BUNDLED_DIR/FuoEvolve.tar.gz" FuoEvolve
rm -rf "$BUNDLED_DIR/FuoEvolve"

BUNDLED_APP_SHA="$(sha256sum "$BUNDLED_DIR/FuoEvolve.tar.gz" | awk '{print $1}')"
BUNDLED_DESKTOP_SHA="$(sha256sum "$BUNDLED_DIR/fuoevolve.desktop" | awk '{print $1}')"
BUNDLED_ICON_SHA="$(sha256sum "$BUNDLED_DIR/fuoevolve.png" | awk '{print $1}')"

cat > "$BUNDLED_DIR/PKGBUILD" <<EOF
pkgname=fuoevolve
pkgver=$VERSION
pkgrel=1
pkgdesc='A cross-platform multi-source music player based on FeelUOwn (bundled JVM)'
arch=('x86_64')
url='https://github.com/feeluown/FuoEvolve'
license=('GPL-3.0-only')
depends=('mpv' 'libsecret' 'webkit2gtk-4.1')
conflicts=('fuoevolve-system-jvm')
options=('!strip')
source=('FuoEvolve.tar.gz' 'fuoevolve.desktop' 'fuoevolve.png')
sha256sums=('$BUNDLED_APP_SHA' '$BUNDLED_DESKTOP_SHA' '$BUNDLED_ICON_SHA')

package() {
  install -d "\$pkgdir/opt/fuoevolve"
  cp -a "\$srcdir/FuoEvolve/." "\$pkgdir/opt/fuoevolve/"
  install -Dm644 "\$srcdir/fuoevolve.desktop" "\$pkgdir/usr/share/applications/fuoevolve.desktop"
  install -Dm644 "\$srcdir/fuoevolve.png" "\$pkgdir/usr/share/icons/hicolor/192x192/apps/fuoevolve.png"
  install -d "\$pkgdir/usr/bin"
  ln -s /opt/fuoevolve/bin/FuoEvolve "\$pkgdir/usr/bin/fuoevolve"
}
EOF

# System-JVM Arch package: retain the optimized application image, native resources and JARs,
# but drop the jpackage launcher/runtime pair and use a distro-aware launcher instead.
cp -a "$APP_IMAGE" "$SYSTEM_DIR/FuoEvolve"
rm -rf "$SYSTEM_DIR/FuoEvolve/bin" "$SYSTEM_DIR/FuoEvolve/runtime"
if [[ ! -d "$SYSTEM_DIR/FuoEvolve/lib/app" ]] || ! find "$SYSTEM_DIR/FuoEvolve/lib/app" -maxdepth 1 -type f -name '*.jar' -print -quit | grep -q .; then
  echo "system-JVM package staging lost application JARs" >&2
  exit 1
fi
if find "$SYSTEM_DIR/FuoEvolve" -path '*/runtime/*' -print -quit | grep -q .; then
  echo "system-JVM package staging still contains a bundled runtime" >&2
  exit 1
fi

tar -C "$SYSTEM_DIR" -czf "$SYSTEM_DIR/FuoEvolve.tar.gz" FuoEvolve
rm -rf "$SYSTEM_DIR/FuoEvolve"

cat > "$SYSTEM_DIR/fuoevolve" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail

readonly MIN_JAVA_MAJOR=21
readonly APPDIR=/usr/lib/fuoevolve/lib/app
readonly MAIN_CLASS=org.feeluown.mobile.desktop.MainKt

java_major() {
  local java_bin="$1"
  local spec
  spec="$("$java_bin" -XshowSettings:properties -version 2>&1 |
    awk -F'= ' '/^[[:space:]]*java\.specification\.version =/ { print $2; exit }')"
  [[ -n "$spec" ]] || return 1
  if [[ "$spec" == 1.* ]]; then
    spec="${spec#1.}"
  fi
  spec="${spec%%.*}"
  [[ "$spec" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$spec"
}

if [[ -n "${FUOEVOLVE_JAVA_HOME:-}" ]]; then
  JAVA_BIN="$FUOEVOLVE_JAVA_HOME/bin/java"
  if [[ ! -x "$JAVA_BIN" ]]; then
    echo "FUOEVOLVE_JAVA_HOME does not contain an executable Java runtime: $JAVA_BIN" >&2
    exit 1
  fi
  JAVA_MAJOR="$(java_major "$JAVA_BIN" || true)"
  if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt "$MIN_JAVA_MAJOR" ]]; then
    echo "FuoEvolve requires Java $MIN_JAVA_MAJOR or later; FUOEVOLVE_JAVA_HOME points to Java ${JAVA_MAJOR:-unknown}." >&2
    exit 1
  fi
else
  JAVA_BIN=""
  BEST_MAJOR=""
  for candidate in /usr/lib/jvm/default-runtime/bin/java /usr/lib/jvm/*/bin/java; do
    [[ -x "$candidate" ]] || continue
    candidate="$(readlink -f "$candidate")"
    major="$(java_major "$candidate" || true)"
    [[ "$major" =~ ^[0-9]+$ ]] || continue
    (( major >= MIN_JAVA_MAJOR )) || continue
    if [[ -z "$BEST_MAJOR" || "$major" -lt "$BEST_MAJOR" ]]; then
      JAVA_BIN="$candidate"
      BEST_MAJOR="$major"
    fi
  done
  if [[ -z "$JAVA_BIN" ]]; then
    echo "FuoEvolve requires an installed Java runtime version $MIN_JAVA_MAJOR or later." >&2
    echo "Install a package that provides java-runtime>=$MIN_JAVA_MAJOR or set FUOEVOLVE_JAVA_HOME." >&2
    exit 1
  fi
fi

readonly JNA_LIBRARY_PATH="$APPDIR/resources/native/mpv:$APPDIR/resources/native/bridges"
exec "$JAVA_BIN" \
  "-Dfuoevolve.appdir=$APPDIR" \
  "-Djna.library.path=$JNA_LIBRARY_PATH" \
  -cp "$APPDIR/*" \
  "$MAIN_CLASS" \
  "$@"
LAUNCHER
chmod 755 "$SYSTEM_DIR/fuoevolve"

SYSTEM_APP_SHA="$(sha256sum "$SYSTEM_DIR/FuoEvolve.tar.gz" | awk '{print $1}')"
SYSTEM_DESKTOP_SHA="$(sha256sum "$SYSTEM_DIR/fuoevolve.desktop" | awk '{print $1}')"
SYSTEM_ICON_SHA="$(sha256sum "$SYSTEM_DIR/fuoevolve.png" | awk '{print $1}')"
SYSTEM_LAUNCHER_SHA="$(sha256sum "$SYSTEM_DIR/fuoevolve" | awk '{print $1}')"

cat > "$SYSTEM_DIR/PKGBUILD" <<EOF
pkgname=fuoevolve-system-jvm
pkgver=$VERSION
pkgrel=1
pkgdesc='A cross-platform multi-source music player based on FeelUOwn (system JVM)'
arch=('x86_64')
url='https://github.com/feeluown/FuoEvolve'
license=('GPL-3.0-only')
depends=('java-runtime>=21' 'mpv' 'libsecret' 'webkit2gtk-4.1')
provides=("fuoevolve=\$pkgver")
conflicts=('fuoevolve')
options=('!strip')
source=('FuoEvolve.tar.gz' 'fuoevolve.desktop' 'fuoevolve.png' 'fuoevolve')
sha256sums=('$SYSTEM_APP_SHA' '$SYSTEM_DESKTOP_SHA' '$SYSTEM_ICON_SHA' '$SYSTEM_LAUNCHER_SHA')

package() {
  install -d "\$pkgdir/usr/lib/fuoevolve"
  cp -a "\$srcdir/FuoEvolve/." "\$pkgdir/usr/lib/fuoevolve/"
  install -Dm755 "\$srcdir/fuoevolve" "\$pkgdir/usr/bin/fuoevolve"
  install -Dm644 "\$srcdir/fuoevolve.desktop" "\$pkgdir/usr/share/applications/fuoevolve.desktop"
  install -Dm644 "\$srcdir/fuoevolve.png" "\$pkgdir/usr/share/icons/hicolor/192x192/apps/fuoevolve.png"
}
EOF

# Build both packages in one clean Arch container so the second variant adds little CI overhead.
# makepkg intentionally runs as a non-root user. Return the bind-mounted work directory to the
# host runner's numeric ownership before Docker exits so host cleanup and artifact upload work.
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
  su builder -c "cd /work/bundled && makepkg --nodeps --noconfirm --cleanbuild"
  su builder -c "cd /work/system-jvm && makepkg --nodeps --noconfirm --cleanbuild"
  namcap /work/bundled/*.pkg.tar.zst /work/system-jvm/*.pkg.tar.zst || true
  chmod a+r /work/bundled/*.pkg.tar.zst /work/system-jvm/*.pkg.tar.zst
'

BUNDLED_PACKAGE="$(find "$BUNDLED_DIR" -maxdepth 1 -type f -name 'fuoevolve-*.pkg.tar.zst' -print -quit)"
SYSTEM_PACKAGE="$(find "$SYSTEM_DIR" -maxdepth 1 -type f -name 'fuoevolve-system-jvm-*.pkg.tar.zst' -print -quit)"
if [[ -z "$BUNDLED_PACKAGE" || -z "$SYSTEM_PACKAGE" ]]; then
  echo "Arch packages were not both produced" >&2
  find "$WORK_DIR" -maxdepth 2 -type f -name '*.pkg.tar.zst' -print >&2
  exit 1
fi

cp "$BUNDLED_PACKAGE" "$OUTPUT_DIR/"
cp "$SYSTEM_PACKAGE" "$OUTPUT_DIR/"
printf 'Built Arch bundled-JVM package: %s\n' "$OUTPUT_DIR/$(basename "$BUNDLED_PACKAGE")"
printf 'Built Arch system-JVM package: %s\n' "$OUTPUT_DIR/$(basename "$SYSTEM_PACKAGE")"
