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
LOCK_FILE="$SCRIPT_DIR/../native-deps.lock"
ICON="$REPO_ROOT/androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png"

if [[ ! -d "$APP_IMAGE" || ! -x "$APP_IMAGE/bin/FuoEvolve" ]]; then
  echo "invalid Compose app image: $APP_IMAGE" >&2
  exit 1
fi
for command in lddtree patchelf curl sha256sum; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required for AppImage packaging" >&2
    exit 1
  }
done

lock_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$LOCK_FILE"
}

download_verified() {
  local url="$1"
  local expected_sha="$2"
  local output="$3"
  curl --fail --location --retry 3 --output "$output" "$url"
  local actual_sha
  actual_sha="$(sha256sum "$output" | awk '{print $1}')"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    echo "SHA-256 mismatch for $url: expected $expected_sha, got $actual_sha" >&2
    exit 1
  fi
}

LIBMPV="$(ldconfig -p 2>/dev/null | awk '/libmpv\.so/{print $NF; exit}')"
if [[ -z "$LIBMPV" || ! -f "$LIBMPV" ]]; then
  LIBMPV="$(find /usr/lib /lib -type f -name 'libmpv.so.*' 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "$LIBMPV" || ! -f "$LIBMPV" ]]; then
  echo "A distribution libmpv package must be installed before building the AppImage" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
APPDIR="$WORK_DIR/FuoEvolve.AppDir"
BUNDLED_APP="$APPDIR/usr/lib/fuoevolve"
MPV_DIR="$BUNDLED_APP/resources/native/mpv"
mkdir -p "$APPDIR/usr/lib" "$MPV_DIR"
cp -a "$APP_IMAGE" "$BUNDLED_APP"

is_base_system_library() {
  local name="$1"
  case "$name" in
    ld-linux*.so*|ld-*.so*|libc.so.*|libm.so.*|libpthread.so.*|libdl.so.*|librt.so.*|libresolv.so.*|libutil.so.*|libnss_*.so.*)
      return 0
      ;;
    # OpenGL/driver-facing libraries must match the host graphics stack. libmpv runs audio-only in
    # FuoEvolve, so keep these as host ABI dependencies rather than shipping a Mesa/driver snapshot.
    libGL.so.*|libGLX.so.*|libOpenGL.so.*|libEGL.so.*|libdrm.so.*|libgbm.so.*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

copy_library() {
  local source="$1"
  local name
  name="$(basename "$source")"
  if is_base_system_library "$name"; then
    return
  fi
  local destination="$MPV_DIR/$name"
  if [[ -f "$destination" ]]; then
    local old_sha new_sha
    old_sha="$(sha256sum "$destination" | awk '{print $1}')"
    new_sha="$(sha256sum "$source" | awk '{print $1}')"
    if [[ "$old_sha" != "$new_sha" ]]; then
      echo "Conflicting ELF library basename while collecting AppImage closure: $name" >&2
      exit 1
    fi
    return
  fi
  cp -L "$source" "$destination"
}

# lddtree resolves the transitive ELF closure, including ffmpeg/codec/audio client libraries that
# JNA cannot discover statically from the Java launcher.
while IFS= read -r library; do
  [[ -f "$library" ]] || continue
  copy_library "$library"
done < <(lddtree -l "$LIBMPV" | awk '!seen[$0]++')

BUNDLED_LIBMPV="$(find "$MPV_DIR" -maxdepth 1 -type f -name 'libmpv.so.*' -print -quit)"
if [[ -z "$BUNDLED_LIBMPV" ]]; then
  echo "libmpv was not copied into the AppImage dependency closure" >&2
  exit 1
fi
ln -sfn "$(basename "$BUNDLED_LIBMPV")" "$MPV_DIR/libmpv.so"

while IFS= read -r library; do
  patchelf --set-rpath '$ORIGIN' "$library" 2>/dev/null || true
done < <(find "$MPV_DIR" -maxdepth 1 -type f -print)

cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
set -e
APPDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MPV_DIR="$APPDIR/usr/lib/fuoevolve/resources/native/mpv"
export LD_LIBRARY_PATH="$MPV_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$APPDIR/usr/lib/fuoevolve/bin/FuoEvolve" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

cat > "$APPDIR/fuoevolve.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=FuoEvolve
Comment=A cross-platform multi-source music player based on FeelUOwn
Exec=FuoEvolve %U
Icon=fuoevolve
Terminal=false
Categories=AudioVideo;Audio;Player;
StartupNotify=true
X-AppImage-Version=$VERSION
EOF
cp "$ICON" "$APPDIR/fuoevolve.png"
ln -sfn fuoevolve.png "$APPDIR/.DirIcon"

TOOL_URL="$(lock_value appimagetool.x64.url)"
TOOL_SHA="$(lock_value appimagetool.x64.sha256)"
RUNTIME_URL="$(lock_value appimage.runtime.x64.url)"
RUNTIME_SHA="$(lock_value appimage.runtime.x64.sha256)"
if [[ -z "$TOOL_URL" || -z "$TOOL_SHA" || -z "$RUNTIME_URL" || -z "$RUNTIME_SHA" ]]; then
  echo "AppImage tool/runtime pins are incomplete in $LOCK_FILE" >&2
  exit 1
fi

APPIMAGETOOL="$WORK_DIR/appimagetool.AppImage"
RUNTIME="$WORK_DIR/runtime-x86_64"
download_verified "$TOOL_URL" "$TOOL_SHA" "$APPIMAGETOOL"
download_verified "$RUNTIME_URL" "$RUNTIME_SHA" "$RUNTIME"
chmod +x "$APPIMAGETOOL"

OUTPUT_FILE="$OUTPUT_DIR/FuoEvolve-$VERSION-linux-x86_64.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$APPIMAGETOOL" \
  --runtime-file "$RUNTIME" \
  "$APPDIR" \
  "$OUTPUT_FILE"
chmod +x "$OUTPUT_FILE"

# Validate that the resulting image is structurally extractable without relying on FUSE.
(
  cd "$WORK_DIR"
  "$OUTPUT_FILE" --appimage-extract >/dev/null
  test -x squashfs-root/usr/lib/fuoevolve/bin/FuoEvolve
  test -e squashfs-root/usr/lib/fuoevolve/resources/native/mpv/libmpv.so
)

printf 'Built self-contained AppImage: %s\n' "$OUTPUT_FILE"
