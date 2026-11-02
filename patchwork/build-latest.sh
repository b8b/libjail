#!/bin/sh
set -eu

# Builds the runtime image on top of freebsd-minimal instead of the ghcr.io
# runtime image, taking the ports packages from the quarterly branch the
# minimal image enables.
#
# The package that carries the containers/common configuration files is
# containers-common in quarterly and container-libs-common in latest, so it is
# a build argument. The binaries built by build.sh are added from bin/.
#
# Run build.sh and build-minimal-image.sh first.

: "${MINIMAL_IMAGE:=freebsd-minimal:$(freebsd-version -u)}"
: "${BUILD_IMAGE:=podman-latest}"
: "${PKG_CONTAINER_LIBS_COMMON:=container-libs-common}"

log() { echo "build-latest.sh: $*" >&2; }
die() { log "$*"; exit 1; }

command -v podman >/dev/null 2>&1 || die "podman not found (pkg install podman)"

here=$(realpath "$(dirname "$0")")

for binary in podman ocijail ngbridge ngbridge-cni netavark; do
    [ -x "$here/bin/$binary" ] || die "$here/bin/$binary missing (run ./build.sh)"
done

podman image inspect "$MINIMAL_IMAGE" >/dev/null 2>&1 ||
    die "$MINIMAL_IMAGE not found (run ./build-minimal-image.sh)"

log "building $BUILD_IMAGE on top of $MINIMAL_IMAGE" \
    "with $PKG_CONTAINER_LIBS_COMMON from the ports repository"

podman \
    --runtime "$here/bin/ocijail" \
    build --network host \
    --build-arg BASE_IMAGE="$MINIMAL_IMAGE" \
    --build-arg PKG_CONTAINER_LIBS_COMMON="$PKG_CONTAINER_LIBS_COMMON" \
    -f "$here/Containerfile" -t "$BUILD_IMAGE" "$@" "$here"

log "built image $BUILD_IMAGE"
