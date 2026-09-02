#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <deb|rpm> <compose-app-image> <version> <output-dir>" >&2
  exit 2
fi

PACKAGE_TYPE="$1"
APP_IMAGE="$2"
VERSION="$3"
OUTPUT_DIR="$4"

if [[ "$PACKAGE_TYPE" != "deb" && "$PACKAGE_TYPE" != "rpm" ]]; then
  echo "unsupported Linux package type: $PACKAGE_TYPE" >&2
  exit 2
fi
if [[ ! -d "$APP_IMAGE" || ! -x "$APP_IMAGE/bin/FuoEvolve" ]]; then
  echo "invalid Compose app image: $APP_IMAGE" >&2
  exit 1
fi
if [[ ! -x "$JAVA_HOME/bin/jpackage" ]]; then
  echo "jpackage is unavailable from JAVA_HOME=$JAVA_HOME" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

COMMON_ARGS=(
  --type "$PACKAGE_TYPE"
  --app-image "$APP_IMAGE"
  --dest "$OUTPUT_DIR"
  --name FuoEvolve
  --app-version "$VERSION"
  --vendor FeelUOwn
  --description "A cross-platform multi-source music player based on FeelUOwn"
  --linux-package-name fuoevolve
  --linux-shortcut
  --linux-menu-group AudioVideo
  --linux-app-category AudioVideo
)

if [[ "$PACKAGE_TYPE" == "deb" ]]; then
  # Debian 12/13 and Ubuntu 24.04+ expose libmpv through libmpv2.
  PACKAGE_DEPS="libmpv2, libsecret-1-0"
  "$JAVA_HOME/bin/jpackage" "${COMMON_ARGS[@]}" \
    --linux-package-deps "$PACKAGE_DEPS"
else
  # Depend on ELF capabilities rather than Fedora package names so compatible RPM distributions
  # may satisfy the dependency from their own libmpv/libsecret package naming.
  PACKAGE_DEPS="libmpv.so.2()(64bit), libsecret-1.so.0()(64bit)"
  "$JAVA_HOME/bin/jpackage" "${COMMON_ARGS[@]}" \
    --linux-package-deps "$PACKAGE_DEPS" \
    --linux-rpm-license-type "GPL-3.0-only"
fi

printf 'Built %s package with external dependencies: %s\n' "$PACKAGE_TYPE" "$PACKAGE_DEPS"
find "$OUTPUT_DIR" -maxdepth 1 -type f -print
