#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <output-dir> <web-login-helper> <audio-capture-library>" >&2
  exit 2
fi

OUTPUT_DIR="$(mkdir -p "$1" && realpath "$1")"
WEB_LOGIN_HELPER="$(realpath "$2")"
AUDIO_CAPTURE_LIBRARY="$(realpath "$3")"

for command in lddtree patchelf ldconfig find; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required to collect the portable Nucleus runtime" >&2
    exit 1
  }
done
if [[ ! -x "$WEB_LOGIN_HELPER" ]]; then
  echo "WebView login helper is missing or not executable: $WEB_LOGIN_HELPER" >&2
  exit 1
fi
if [[ ! -f "$AUDIO_CAPTURE_LIBRARY" ]]; then
  echo "System audio capture library is missing: $AUDIO_CAPTURE_LIBRARY" >&2
  exit 1
fi

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

is_base_system_library() {
  local name="$1"
  case "$name" in
    ld-linux*.so*|ld-*.so*|libc.so.*|libm.so.*|libpthread.so.*|libdl.so.*|librt.so.*|libresolv.so.*|libutil.so.*|libnss_*.so.*)
      return 0
      ;;
    # Graphics-driver facing libraries must stay matched to the host stack.
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
  mkdir -p "$destination_dir"
  local destination="$destination_dir/$name"
  if [[ -f "$destination" ]]; then
    local old_sha new_sha
    old_sha="$(sha256sum "$destination" | awk '{print $1}')"
    new_sha="$(sha256sum "$source" | awk '{print $1}')"
    if [[ "$old_sha" != "$new_sha" ]]; then
      echo "Conflicting ELF library basename while collecting Nucleus closure: $name" >&2
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

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
MPV_LIB_DIR="$OUTPUT_DIR/lib"
LIBSECRET_LIB_DIR="$OUTPUT_DIR/libsecret"
WEBVIEW_ROOT="$OUTPUT_DIR/webview"
WEBVIEW_LIB_DIR="$WEBVIEW_ROOT/lib"
WEBKIT_RUNTIME_DIR="$WEBVIEW_ROOT/webkit2gtk-4.1"
GIO_MODULE_DIR="$WEBVIEW_ROOT/gio/modules"
AUDIO_ROOT="$OUTPUT_DIR/audio"
mkdir -p "$MPV_LIB_DIR" "$LIBSECRET_LIB_DIR" "$WEBVIEW_LIB_DIR" "$WEBKIT_RUNTIME_DIR" "$GIO_MODULE_DIR" "$AUDIO_ROOT"

LIBMPV="$(find_shared_library 'libmpv[.]so' 'libmpv.so.*')"
LIBSECRET="$(find_shared_library 'libsecret-1[.]so[.]0' 'libsecret-1.so.0*')"
if [[ -z "$LIBMPV" || ! -f "$LIBMPV" ]]; then
  echo "libmpv is required before collecting the portable runtime" >&2
  exit 1
fi
if [[ -z "$LIBSECRET" || ! -f "$LIBSECRET" ]]; then
  echo "libsecret is required before collecting the portable runtime" >&2
  exit 1
fi

copy_library_to "$LIBMPV" "$MPV_LIB_DIR"
copy_elf_closure "$LIBMPV" "$MPV_LIB_DIR"
copy_library_to "$LIBSECRET" "$LIBSECRET_LIB_DIR"
copy_elf_closure "$LIBSECRET" "$LIBSECRET_LIB_DIR"
copy_library_to "$AUDIO_CAPTURE_LIBRARY" "$AUDIO_ROOT"
copy_elf_closure "$AUDIO_CAPTURE_LIBRARY" "$AUDIO_ROOT"

BUNDLED_LIBMPV="$(find "$MPV_LIB_DIR" -maxdepth 1 -type f -name 'libmpv.so.*' -print -quit)"
BUNDLED_LIBSECRET="$(find "$LIBSECRET_LIB_DIR" -maxdepth 1 -type f -name 'libsecret-1.so.0*' -print -quit)"
BUNDLED_AUDIO_CAPTURE="$(find "$AUDIO_ROOT" -maxdepth 1 -type f -name 'libfuoevolve_audio_capture.so' -print -quit)"
if [[ -z "$BUNDLED_LIBMPV" || -z "$BUNDLED_LIBSECRET" || -z "$BUNDLED_AUDIO_CAPTURE" ]]; then
  echo "Portable runtime collection did not capture libmpv/libsecret/audio capture" >&2
  exit 1
fi
ln -sfn "$(basename "$BUNDLED_LIBMPV")" "$MPV_LIB_DIR/libmpv.so"
ln -sfn "$(basename "$BUNDLED_LIBSECRET")" "$LIBSECRET_LIB_DIR/libsecret-1.so"

# wry links against WebKitGTK. Bundle the helper's ELF closure and WebKit's separately executed
# subprocesses because those processes do not appear in the helper's DT_NEEDED graph.
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

SYSTEM_GIO_TLS_MODULE="$(find /usr/lib /lib -type f -path '*/gio/modules/libgiognutls.so' -print -quit 2>/dev/null || true)"
if [[ -z "$SYSTEM_GIO_TLS_MODULE" || ! -f "$SYSTEM_GIO_TLS_MODULE" ]]; then
  echo "GLib GnuTLS module was not found; install glib-networking" >&2
  exit 1
fi
cp -L "$SYSTEM_GIO_TLS_MODULE" "$GIO_MODULE_DIR/libgiognutls.so"
copy_elf_closure "$SYSTEM_GIO_TLS_MODULE" "$WEBVIEW_LIB_DIR"

while IFS= read -r library; do
  patchelf --set-rpath '$ORIGIN' "$library" 2>/dev/null || true
done < <(find "$MPV_LIB_DIR" "$LIBSECRET_LIB_DIR" "$WEBVIEW_LIB_DIR" "$AUDIO_ROOT" -maxdepth 1 -type f -name '*.so*' -print)
while IFS= read -r executable; do
  patchelf --set-rpath '$ORIGIN/../lib:$ORIGIN/../../lib' "$executable" 2>/dev/null || true
done < <(find "$WEBKIT_RUNTIME_DIR" -maxdepth 1 -type f -perm -u+x -print)
patchelf --set-rpath '$ORIGIN/../../lib' "$WEBKIT_RUNTIME_DIR/injected-bundle/libwebkit2gtkinjectedbundle.so" 2>/dev/null || true
patchelf --set-rpath '$ORIGIN/../../lib' "$GIO_MODULE_DIR/libgiognutls.so" 2>/dev/null || true

echo "Prepared portable Nucleus Linux runtime at $OUTPUT_DIR"
du -sh "$OUTPUT_DIR"
