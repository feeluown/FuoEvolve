#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <nucleus-runtime-dir> <version> <output-dir>" >&2
  exit 2
fi

RUNTIME_DIR="$(realpath "$1")"
VERSION="$2"
OUTPUT_DIR="$(mkdir -p "$3" && realpath "$3")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ICON="$REPO_ROOT/androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
BINARY_NAME="fuoevolve-nucleus-poc"
PACKAGE_NAME="fuoevolve-nucleus-poc"
INSTALL_ROOT="/opt/fuoevolve-nucleus-poc"

if [[ ! -d "$RUNTIME_DIR" || ! -x "$RUNTIME_DIR/$BINARY_NAME" ]]; then
  echo "invalid Nucleus runtime directory: $RUNTIME_DIR" >&2
  exit 1
fi
if [[ ! -f "$RUNTIME_DIR/native/lib/libfuoevolve_mpv_jni.so" ]]; then
  echo "Nucleus runtime is missing the libmpv JNI bridge" >&2
  exit 1
fi
if [[ ! -x "$RUNTIME_DIR/native/helpers/fuoevolve-web-login" ]]; then
  echo "Nucleus runtime is missing the WebView login helper" >&2
  exit 1
fi
if [[ ! -f "$ICON" ]]; then
  echo "desktop package icon is missing: $ICON" >&2
  exit 1
fi
if ! command -v dpkg-deb >/dev/null 2>&1; then
  echo "dpkg-deb is required to build the Nucleus Linux package" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
PACKAGE_ROOT="$WORK_DIR/root"
mkdir -p \
  "$PACKAGE_ROOT/DEBIAN" \
  "$PACKAGE_ROOT$INSTALL_ROOT" \
  "$PACKAGE_ROOT/usr/bin" \
  "$PACKAGE_ROOT/usr/share/applications" \
  "$PACKAGE_ROOT/usr/share/icons/hicolor/192x192/apps"

cp -a "$RUNTIME_DIR/." "$PACKAGE_ROOT$INSTALL_ROOT/"
ln -s "$INSTALL_ROOT/$BINARY_NAME" "$PACKAGE_ROOT/usr/bin/fuoevolve-nucleus-poc"
cp "$ICON" "$PACKAGE_ROOT/usr/share/icons/hicolor/192x192/apps/fuoevolve-nucleus-poc.png"

cat > "$PACKAGE_ROOT/usr/share/applications/fuoevolve-nucleus-poc.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=FuoEvolve (Nucleus)
Comment=FuoEvolve native desktop runtime
Exec=/usr/bin/fuoevolve-nucleus-poc %U
Icon=fuoevolve-nucleus-poc
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupNotify=true
EOF

# Keep the same distro-managed native dependency model as the existing JVM Linux package. The
# Native Image is JVM-free, but libmpv, Secret Service and WebKitGTK remain host ABI dependencies.
PACKAGE_DEPS="libmpv2, libsecret-1-0, libwebkit2gtk-4.1-0, libx11-6, libgl1, libfontconfig1, libxkbcommon-x11-0"
INSTALLED_SIZE="$(du -sk "$PACKAGE_ROOT" | awk '{print $1}')"
cat > "$PACKAGE_ROOT/DEBIAN/control" <<EOF
Package: $PACKAGE_NAME
Version: $VERSION
Section: sound
Priority: optional
Architecture: amd64
Maintainer: FeelUOwn <feeluown@users.noreply.github.com>
Depends: $PACKAGE_DEPS
Installed-Size: $INSTALLED_SIZE
Homepage: https://github.com/feeluown/FuoEvolve
Description: FuoEvolve Nucleus native desktop runtime
 JVM-free Linux Native Image runtime with direct JNI libmpv playback.
EOF

OUTPUT_FILE="$OUTPUT_DIR/${PACKAGE_NAME}_${VERSION}_amd64.deb"
dpkg-deb --build --root-owner-group "$PACKAGE_ROOT" "$OUTPUT_FILE"

# The raw graalvm-app directory is not a portable Linux distribution: its JNI bridge intentionally
# resolves libmpv from the host. Fail packaging if that requirement is not encoded in package
# metadata, so CI cannot accidentally publish a runner-dependent artifact again.
DEPS="$(dpkg-deb -f "$OUTPUT_FILE" Depends)"
for dependency in libmpv2 libsecret-1-0 libwebkit2gtk-4.1-0; do
  if [[ "$DEPS" != *"$dependency"* ]]; then
    echo "Nucleus DEB is missing required dependency metadata: $dependency" >&2
    exit 1
  fi
done

echo "Built $OUTPUT_FILE"
echo "External runtime dependencies: $DEPS"
