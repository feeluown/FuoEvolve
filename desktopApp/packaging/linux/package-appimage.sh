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

find_shared_library() {
  local pattern="$1"
  local fallback_name="$2"
  local result
  result="$(ldconfig -p 2>/dev/null | awk -v pattern="$pattern" '$0 ~ pattern {print $NF; exit}')"
  if [[ -z "$result" || ! -f "$result" ]]; then
    result="$(find /usr/lib /lib -type f -name "$fallback_name" 2>/dev/null | head -n 1 || true)"
  fi
  printf '%s' "$result"
}

LIBMPV="$(find_shared_library 'libmpv[.]so' 'libmpv.so.*')"
LIBSECRET="$(find_shared_library 'libsecret-1[.]so[.]0' 'libsecret-1.so.0*')"
if [[ -z "$LIBMPV" || ! -f "$LIBMPV" ]]; then
  echo "A distribution libmpv package must be installed before building the AppImage" >&2
  exit 1
fi
if [[ -z "$LIBSECRET" || ! -f "$LIBSECRET" ]]; then
  echo "A distribution libsecret package must be installed before building the AppImage" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
APPDIR="$WORK_DIR/FuoEvolve.AppDir"
BUNDLED_APP="$APPDIR/usr/lib/fuoevolve"
NATIVE_LIB_DIR="$BUNDLED_APP/resources/native/mpv"
WEBVIEW_ROOT="$BUNDLED_APP/resources/native/webview"
WEBVIEW_LIB_DIR="$WEBVIEW_ROOT/lib"
WEBKIT_RUNTIME_DIR="$WEBVIEW_ROOT/webkit2gtk-4.1"
GIO_MODULE_DIR="$WEBVIEW_ROOT/gio/modules"
mkdir -p "$NATIVE_LIB_DIR" "$WEBVIEW_LIB_DIR" "$WEBKIT_RUNTIME_DIR" "$GIO_MODULE_DIR"
# Copy the contents of the Compose app image into the runtime root. Copying the source directory
# itself into the already-created BUNDLED_APP directory would add an unintended FuoEvolve/ layer.
cp -a "$APP_IMAGE/." "$BUNDLED_APP/"

WEB_LOGIN_HELPER="$BUNDLED_APP/resources/native/helpers/fuoevolve-web-login"
if [[ ! -x "$WEB_LOGIN_HELPER" ]]; then
  echo "Compose app image is missing the desktop WebView login helper" >&2
  exit 1
fi

is_base_system_library() {
  local name="$1"
  case "$name" in
    ld-linux*.so*|ld-*.so*|libc.so.*|libm.so.*|libpthread.so.*|libdl.so.*|librt.so.*|libresolv.so.*|libutil.so.*|libnss_*.so.*)
      return 0
      ;;
    # OpenGL/driver-facing libraries must match the host graphics stack. Keep these as host ABI
    # dependencies rather than shipping a Mesa/driver snapshot into the portable image.
    libGL.so.*|libGLX.so.*|libOpenGL.so.*|libEGL.so.*|libdrm.so.*|libgbm.so.*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

copy_library_to() {
  local source="$1"
  local destination_dir="$2"
  local name
  name="$(basename "$source")"
  if is_base_system_library "$name"; then
    return
  fi
  local destination="$destination_dir/$name"
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

copy_elf_closure() {
  local root="$1"
  local destination_dir="$2"
  while IFS= read -r library; do
    [[ -f "$library" ]] || continue
    [[ "$library" == "$root" ]] && continue
    copy_library_to "$library" "$destination_dir"
  done < <(lddtree -l "$root" | awk '!seen[$0]++')
}

# libmpv contributes codecs/audio clients; libsecret is bundled as a client library while the
# Secret Service D-Bus daemon itself remains a host service.
for root_library in "$LIBMPV" "$LIBSECRET"; do
  copy_library_to "$root_library" "$NATIVE_LIB_DIR"
  copy_elf_closure "$root_library" "$NATIVE_LIB_DIR"
done

BUNDLED_LIBMPV="$(find "$NATIVE_LIB_DIR" -maxdepth 1 -type f -name 'libmpv.so.*' -print -quit)"
BUNDLED_LIBSECRET="$(find "$NATIVE_LIB_DIR" -maxdepth 1 -type f -name 'libsecret-1.so.0*' -print -quit)"
if [[ -z "$BUNDLED_LIBMPV" ]]; then
  echo "libmpv was not copied into the AppImage dependency closure" >&2
  exit 1
fi
if [[ -z "$BUNDLED_LIBSECRET" ]]; then
  echo "libsecret was not copied into the AppImage dependency closure" >&2
  exit 1
fi
ln -sfn "$(basename "$BUNDLED_LIBMPV")" "$NATIVE_LIB_DIR/libmpv.so"

# wry links to WebKitGTK on Linux. Bundle both the helper's ELF closure and WebKit's separately
# executed subprocesses; the latter are not visible from the helper's normal DT_NEEDED graph.
copy_elf_closure "$WEB_LOGIN_HELPER" "$WEBVIEW_LIB_DIR"
SYSTEM_WEBKIT_RUNTIME_DIR="$(dirname "$(find /usr/lib /lib -type f -path '*/webkit2gtk-4.1/WebKitNetworkProcess' -print -quit 2>/dev/null || true)")"
if [[ -z "$SYSTEM_WEBKIT_RUNTIME_DIR" || ! -x "$SYSTEM_WEBKIT_RUNTIME_DIR/WebKitNetworkProcess" ]]; then
  echo "WebKitGTK 4.1 subprocess runtime was not found" >&2
  exit 1
fi

for process_name in WebKitNetworkProcess WebKitWebProcess WebKitGPUProcess; do
  process_path="$SYSTEM_WEBKIT_RUNTIME_DIR/$process_name"
  [[ -x "$process_path" ]] || continue
  cp -L "$process_path" "$WEBKIT_RUNTIME_DIR/$process_name"
  chmod +x "$WEBKIT_RUNTIME_DIR/$process_name"
  copy_elf_closure "$process_path" "$WEBVIEW_LIB_DIR"
done

SYSTEM_INJECTED_BUNDLE="$(find "$SYSTEM_WEBKIT_RUNTIME_DIR" -type f -name 'libwebkit2gtkinjectedbundle.so' -print -quit 2>/dev/null || true)"
if [[ -z "$SYSTEM_INJECTED_BUNDLE" || ! -f "$SYSTEM_INJECTED_BUNDLE" ]]; then
  echo "WebKitGTK injected bundle was not found" >&2
  exit 1
fi
mkdir -p "$WEBKIT_RUNTIME_DIR/injected-bundle"
cp -L "$SYSTEM_INJECTED_BUNDLE" "$WEBKIT_RUNTIME_DIR/injected-bundle/libwebkit2gtkinjectedbundle.so"
copy_elf_closure "$SYSTEM_INJECTED_BUNDLE" "$WEBVIEW_LIB_DIR"

# HTTPS support is dynamically discovered by GIO and therefore is not part of WebKit's direct ELF
# closure. Bundle the GLib TLS module and make it discoverable through GIO_EXTRA_MODULES.
SYSTEM_GIO_TLS_MODULE="$(find /usr/lib /lib -type f -path '*/gio/modules/libgiognutls.so' -print -quit 2>/dev/null || true)"
if [[ -z "$SYSTEM_GIO_TLS_MODULE" || ! -f "$SYSTEM_GIO_TLS_MODULE" ]]; then
  echo "GLib GnuTLS module was not found; install glib-networking" >&2
  exit 1
fi
cp -L "$SYSTEM_GIO_TLS_MODULE" "$GIO_MODULE_DIR/libgiognutls.so"
copy_elf_closure "$SYSTEM_GIO_TLS_MODULE" "$WEBVIEW_LIB_DIR"

while IFS= read -r library; do
  patchelf --set-rpath '$ORIGIN' "$library" 2>/dev/null || true
done < <(find "$NATIVE_LIB_DIR" "$WEBVIEW_LIB_DIR" -maxdepth 1 -type f -print)
while IFS= read -r executable; do
  patchelf --set-rpath '$ORIGIN/../lib:$ORIGIN/../../lib' "$executable" 2>/dev/null || true
done < <(find "$WEBKIT_RUNTIME_DIR" -maxdepth 1 -type f -perm -u+x -print)
patchelf --set-rpath '$ORIGIN/../../lib' "$WEBKIT_RUNTIME_DIR/injected-bundle/libwebkit2gtkinjectedbundle.so" 2>/dev/null || true
patchelf --set-rpath '$ORIGIN/../../lib' "$GIO_MODULE_DIR/libgiognutls.so" 2>/dev/null || true

cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
set -e
APPDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
APP_ROOT="$APPDIR/usr/lib/fuoevolve"
NATIVE_LIB_DIR="$APP_ROOT/resources/native/mpv"
WEBVIEW_ROOT="$APP_ROOT/resources/native/webview"
WEBVIEW_LIB_DIR="$WEBVIEW_ROOT/lib"
export LD_LIBRARY_PATH="$NATIVE_LIB_DIR:$WEBVIEW_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export WEBKIT_EXEC_PATH="$WEBVIEW_ROOT/webkit2gtk-4.1"
export WEBKIT_INJECTED_BUNDLE_PATH="$WEBVIEW_ROOT/webkit2gtk-4.1/injected-bundle"
export GIO_EXTRA_MODULES="$WEBVIEW_ROOT/gio/modules${GIO_EXTRA_MODULES:+:$GIO_EXTRA_MODULES}"
exec "$APP_ROOT/bin/FuoEvolve" "$@"
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
  test -x squashfs-root/usr/lib/fuoevolve/bin/FuoEvolve || {
    echo "Packaged AppImage is missing the FuoEvolve launcher at the expected root" >&2
    exit 1
  }
  test -e squashfs-root/usr/lib/fuoevolve/resources/native/mpv/libmpv.so || {
    echo "Packaged AppImage is missing the bundled libmpv loader alias" >&2
    exit 1
  }
  find squashfs-root/usr/lib/fuoevolve/resources/native/mpv -name 'libsecret-1.so.0*' -print -quit | grep -q . || {
    echo "Packaged AppImage is missing bundled libsecret" >&2
    exit 1
  }
  test -x squashfs-root/usr/lib/fuoevolve/resources/native/helpers/fuoevolve-web-login || {
    echo "Packaged AppImage is missing the WebView login helper" >&2
    exit 1
  }
  test -x squashfs-root/usr/lib/fuoevolve/resources/native/webview/webkit2gtk-4.1/WebKitNetworkProcess || {
    echo "Packaged AppImage is missing the WebKit network process" >&2
    exit 1
  }
  test -f squashfs-root/usr/lib/fuoevolve/resources/native/webview/webkit2gtk-4.1/injected-bundle/libwebkit2gtkinjectedbundle.so || {
    echo "Packaged AppImage is missing the WebKit injected bundle" >&2
    exit 1
  }
  test -f squashfs-root/usr/lib/fuoevolve/resources/native/webview/gio/modules/libgiognutls.so || {
    echo "Packaged AppImage is missing the GIO TLS module" >&2
    exit 1
  }
)

printf 'Built self-contained AppImage: %s\n' "$OUTPUT_FILE"
