#!/bin/sh
set -eu

: "${BASE_IMAGE:=ghcr.io/freebsd/freebsd-toolchain:15.1}"
: "${BOOTSTRAP_CONTAINER:=podman-bootstrap}"
: "${BOOTSTRAP_JAIL:=podman-bootstrap}"
: "${IMAGE:=podman}"

here=$(realpath "$(dirname "$0")")
tree="$here"
cd "$tree"

: "${RUNTIME:=$here/bin/ocijail}"

# Every buildah call uses this configuration: no runtime while building, CNI as
# the network backend, and the helper directory the CNI plugins live in.
export CONTAINERS_CONF="$here/containers-bootstrap.conf"

log() { echo "build.sh: $*" >&2; }
die() { echo "build.sh: $*" >&2; exit 1; }

on_exit() {
    status=$?
    if [ "$status" -eq 0 ] || [ "${bootstrapped:-0}" != 1 ]; then
        return
    fi
    log "build failed; left $BOOTSTRAP_JAIL on $BOOTSTRAP_CONTAINER in place"
    log "remove them with: jail -r $BOOTSTRAP_JAIL; umount ${root:-<rootfs>}/dev;" \
        "buildah rm $BOOTSTRAP_CONTAINER"
}
trap on_exit EXIT

check_os() {
    osreldate=$(sysctl -n kern.osreldate)
    if [ "$osreldate" -lt 1501000 ]; then
        die "FreeBSD 15.1 or later required (kern.osreldate is $osreldate)"
    fi
}

check_chroot() {
    # buildah and the OCI runtime call chroot(2) with directory descriptors
    # still open, which only works with the value 2. It cannot be set from a
    # jail, and it is a host setting, so only report it.
    value=$(sysctl -n kern.chroot_allow_open_directories)
    [ "$value" = 2 ] ||
        die "kern.chroot_allow_open_directories is $value, must be 2" \
            "(set it on the host: sysctl kern.chroot_allow_open_directories=2," \
            "and keep it in /etc/sysctl.conf; this script will not change it)"
}

check_jail() {
    if [ "$(sysctl -n security.jail.jailed)" != 1 ]; then
        return
    fi

    # A jailed process cannot query its own parameters, so ask the kernel: a
    # child jail can only be created with parameters our jail has, and only if
    # children.max is not zero. Probing one parameter at a time tells us exactly
    # which ones are missing.
    check_name=podman-build-check
    jail -r "$check_name" >/dev/null 2>&1 || true

    probe() {
        if jid=$(jail -ci name="$check_name" path=/ persist "$@" 2>&1); then
            jail -r "$jid" >/dev/null 2>&1 || true
            return 0
        fi
        probe_error=$jid
        return 1
    }

    if ! probe; then
        die "inside a jail that cannot create jails at all:" \
            "$probe_error (children.max is 0, or the jail is not allowed to" \
            "create child jails)"
    fi

    missing=
    for allow in allow.mount allow.mount.devfs allow.mount.fdescfs \
            allow.mount.nullfs allow.mount.tmpfs allow.chflags \
            allow.raw_sockets; do
        probe "$allow" || missing="${missing:+$missing }$allow"
    done
    if [ -n "$missing" ]; then
        die "inside a jail that lacks the parameters a container build needs:" \
            "$missing (add them to the jail's configuration, see" \
            "examples/nested-podman/README.md for the set that is used there)"
    fi

    probe allow.mount.zfs ||
        log "note: no allow.mount.zfs; needed if buildah uses the zfs storage driver"
}

# check_sources reports whether the trees the build uses are there and match the
# commits the superproject records. The build copies the worktrees, not the
# pinned commits, so a dirty submodule means the binaries do not match what the
# superproject says.
check_sources() {
    command -v git >/dev/null 2>&1 ||
        { log "note: git is not installed, cannot check the submodules"; return 0; }
    [ -e "$here/.git" ] ||
        { log "note: $here is not a git checkout, cannot check the submodules"; return 0; }
    [ -f "$here/.gitmodules" ] || return 0

    missing=
    for path in $(git -C "$here" config --file "$here/.gitmodules" --get-regexp '^submodule\..*\.path$' | awk '{ print $2 }'); do
        state=$(git -C "$here" submodule status -- "$path" 2>/dev/null | cut -c1)
        case "$state" in
        -) missing="${missing:+$missing }$path" ;;
        U) die "$path has a conflict, resolve it before building" ;;
        +) log "warning: $path is not at the commit $here records" ;;
        esac
        if [ -n "$(git -C "$here/$path" status --porcelain --untracked-files=no 2>/dev/null)" ]; then
            log "warning: $path has uncommitted changes, they are what gets built"
        fi
    done
    [ -z "$missing" ] ||
        die "submodule(s) not checked out:$missing (run: git submodule update --init --recursive)"
}

check_buildah() {
    command -v buildah >/dev/null 2>&1 ||
        die "buildah not found (pkg install buildah)"
}

check_distfiles() {
    for archive in main.zip v2.3.2.zip include.zip; do
        [ -f "ocijail/distfiles/$archive" ] ||
            die "ocijail/distfiles/$archive missing (run ./prefetch-deps.sh)"
    done
}

clean_bootstrap() {
    jail -r "$BOOTSTRAP_JAIL" >/dev/null 2>&1 || true
    if root=$(buildah mount "$BOOTSTRAP_CONTAINER" 2>/dev/null); then
        umount "$root/dev" >/dev/null 2>&1 || true
    fi
    buildah rm "$BOOTSTRAP_CONTAINER" >/dev/null 2>&1 || true
}

bootstrap_rootfs() {
    clean_bootstrap
    buildah from --pull=always --quiet --name "$BOOTSTRAP_CONTAINER" \
        "$BASE_IMAGE" >/dev/null

    root=$(buildah mount "$BOOTSTRAP_CONTAINER")
    [ -n "$root" ] && [ -d "$root" ] ||
        die "cannot mount $BOOTSTRAP_CONTAINER (with the zfs storage driver the" \
            "jail needs allow.mount.zfs)"

    # pkg inside the bootstrap rootfs needs working resolvers.
    cp /etc/resolv.conf "$root/etc/resolv.conf"

    bootstrapped=1
    log "bootstrap rootfs at $root"
}

prepare_jail() {
    mount -t devfs devfs "$root/dev"
    jail -c name="$BOOTSTRAP_JAIL" path="$root" persist ip4=inherit ip6=inherit
    pkg -j "$BOOTSTRAP_JAIL" install -y bazel5 bash go gpgme gmake gnugrep pkgconf

    log "bootstrap jail $BOOTSTRAP_JAIL ready on $root"
}

copy_sources() {
    src="$root/usr/src"
    mkdir -p "$src"/ocijail
    cp -a podman "$src/"
    cp -a container-libs "$src/"
    cp -a ocijail/WORKSPACE ocijail/BUILD ocijail/.bazelrc ocijail/ocijail ocijail/third_party ocijail/distfiles ocijail/tools "$src/ocijail/"
    cp -a ngbridge "$src/"
    log "sources copied to $src"
}

# use_container_libs puts the container-libs checkout in place of the vendored
# copy of every module it provides. Podman is built with -mod=vendor, so the
# vendored tree is what gets compiled: without this, changes in container-libs
# would not reach the build, and with it they do, without a re-vendor and
# without committing anything to the podman tree.
#
# The vendored copies are replaced wholesale, which covers edited files and
# added files. A new package would also need a line in vendor/modules.txt, so
# that still wants a "go mod vendor" on the host.
use_container_libs() {
    src="$root/usr/src"
    [ -d "$src/container-libs" ] || return 0
    for module_dir in "$src/container-libs"/*/; do
        [ -f "${module_dir}go.mod" ] || continue
        module=$(awk '$1 == "module" { print $2; exit }' "${module_dir}go.mod")
        [ -n "$module" ] || continue
        vendored="$src/podman/vendor/$module"
        [ -d "$vendored" ] || continue
        rm -rf "$vendored"
        cp -a "$module_dir" "$vendored"
        # go mod vendor copies packages, not the module's own files.
        rm -f "$vendored/go.mod" "$vendored/go.sum"
        rm -rf "$vendored/vendor"
        log "using $module from container-libs"
    done
}

build_podman() {
    src=/usr/src/podman

    # bin/podman depends on the sources only, not on the build tags, and
    # copy_sources preserves the mtime of a binary left in the checkout. A stale
    # one would be installed as it is, without the cni tag, and fail at run time
    # with "could not find netavark".
    jexec "$BOOTSTRAP_JAIL" /bin/sh -c "rm -f $src/bin/podman"

    # The copy in the jail carries no usable git directory, so the version would
    # be empty; hand the pinned commit over instead.
    commit=$(git -C "$here/podman" rev-parse HEAD 2>/dev/null || true)
    if [ -n "$commit" ] && [ -n "$(git -C "$here/podman" status --porcelain 2>/dev/null)" ]; then
        commit="$commit-dirty"
    fi
    make_flags="EXTRA_BUILDTAGS=cni"
    [ -z "$commit" ] || make_flags="$make_flags GIT_COMMIT=$commit"

    # The Makefile adds the cni tag on FreeBSD by itself; passing it again keeps
    # a build with a different GOOS from losing the only backend that works here.
    jexec "$BOOTSTRAP_JAIL" /usr/bin/env \
        HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
        /bin/sh -c "cd $src && gmake podman $make_flags"
    jexec "$BOOTSTRAP_JAIL" /bin/sh -c \
        "cp $src/bin/podman /root/podman"
    # Without the cni tag podman only has the netavark backend, which does not
    # exist on FreeBSD, and every network setup fails at run time with
    # "could not find netavark". The tags end up in the binary, so check them.
    jexec "$BOOTSTRAP_JAIL" go version -m /root/podman |
        grep -q -- '-tags=[^[:space:]]*cni' ||
        die "podman was built without the cni tag: $(jexec "$BOOTSTRAP_JAIL" go version -m /root/podman | grep -- '-tags=' || true)"
    mkdir -p "$here/bin"
    install -m 755 "$root/root/podman" "$here/bin/podman"
    log "built $here/bin/podman"
}

build_ocijail() {
    src=/usr/src/ocijail

    jexec "$BOOTSTRAP_JAIL" /usr/bin/env \
        HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
        /bin/sh -c "cd $src/ocijail && exec bazel build --workspace_status_command=/usr/bin/true //ocijail:ocijail"
    jexec "$BOOTSTRAP_JAIL" /bin/sh -c \
        "cp $src/bazel-bin/ocijail/ocijail /root/ocijail"
    [ -f "$root/root/ocijail" ] ||
        die "bazel did not produce $src/bazel-bin/ocijail/ocijail"
    mkdir -p "$here/bin"
    install -m 755 "$root/root/ocijail" "$here/bin/ocijail"
    log "built $here/bin/ocijail"
}

build_ngbridge() {
    src=/usr/src/ngbridge

    jexec "$BOOTSTRAP_JAIL" /usr/bin/env \
        HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
        /bin/sh -c "cd $src && go build -o ngbridge . && go build -o ngbridge-cni ./cmd/ngbridge-cni"
    mkdir -p "$here/bin"
    install -m 755 "$root/usr/src/ngbridge/ngbridge" "$here/bin/ngbridge"
    install -m 755 "$root/usr/src/ngbridge/ngbridge-cni" "$here/bin/ngbridge-cni"
    log "built $here/bin/ngbridge"
}

build_image() {
    # The image only adds the binary built above; its RUN step needs a runtime,
    # which is the one we just built unless RUNTIME says otherwise. Its base
    # image comes from the Containerfile's ARG, not from BASE_IMAGE.
    "$here/bin/podman" --runtime "$RUNTIME" build \
        -f "$here/Containerfile" -t "$IMAGE" "$here"
    log "built image $IMAGE"
}

check_os
check_chroot
check_jail
check_sources
check_buildah
check_distfiles

log "bootstrap base image $BASE_IMAGE"
bootstrap_rootfs
prepare_jail
copy_sources
use_container_libs
build_podman
build_ocijail
build_ngbridge
# Only reached when the build above succeeded (set -e), so this is the point to
# throw the bootstrap rootfs away.
clean_bootstrap
build_image

# The command goes to stdout, unprefixed, so it can be copied; everything else
# this script says is on stderr.
log "run the image with:"
cat <<EOT
CONTAINERS_CONF=$here/containers-bootstrap.conf \
"$here/bin/podman" \\
    --runtime $RUNTIME \\
    --hooks-dir $here/ocijail/examples/nested-podman/hooks.d \\
    run --rm -it \\
    --annotation org.freebsd.jail.vnet=new \\
    --annotation org.freebsd.jail.allow.mount=true \\
    --annotation org.freebsd.jail.allow.mount.devfs=true \\
    --annotation org.freebsd.jail.allow.mount.fdescfs=true \\
    --annotation org.freebsd.jail.allow.mount.nullfs=true \\
    --annotation org.freebsd.jail.allow.mount.tmpfs=true \\
    --annotation org.freebsd.jail.allow.chflags=true \\
    --annotation org.freebsd.jail.children.max=8 \\
    --device /dev/pf \\
    $IMAGE
EOT
