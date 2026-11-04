#!/bin/sh
set -eu

command -v podman >/dev/null 2>&1 || die "podman not found (pkg install podman)"

here=$(realpath "$(dirname "$0")")

tag="$("$here"/bin/podman --version)"
tag="${tag#podman version }"

podman \
    --runtime "$here/bin/ocijail" \
    build --network=host \
    -f "$here/Containerfile" -t podman:"$tag" "$here"

podman tag podman:"$tag" podman:latest
