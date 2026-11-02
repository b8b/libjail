#!/bin/sh
set -eu

: "${BASE_IMAGE:=ghcr.io/freebsd/freebsd-toolchain:15.1}"
: "${BUILD_IMAGE:=podman}"

here=$(realpath "$(dirname "$0")")
tree="$here"
cd "$tree"

log() { echo "build.sh: $*" >&2; }
die() { echo "build.sh: $*" >&2; exit 1; }

bootstrap_cid=
bootstrap_jid=

on_exit() {
    status="$?"
    if [ -n "$bootstrap_jid" ]; then
        jail -r "$bootstrap_jid"
        umount -f "$root/dev"
    fi
    if [ -n "$bootstrap_cid" ]; then
        if [ "$status" -eq 0 ]; then
            podman rm "$bootstrap_cid"
        else
            log "build failed; left $bootstrap_cid in place"
            log "remove with: podman rm $bootstrap_cid"
        fi
    fi
}
trap on_exit EXIT

check_os() {
    osreldate=$(sysctl -n kern.osreldate)
    if [ "$osreldate" -lt 1501000 ]; then
        die "FreeBSD 15.1 or later required (kern.osreldate is $osreldate)"
    fi
}

check_chroot() {
    # podman calls chroot(2) with directory descriptors
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

    if ! jail -c path=/ command=/usr/bin/true; then
        die "inside a jail that cannot create jails at all:" \
            " (children.max is 0, or the jail is not allowed to" \
            "create child jails)"
    fi

    missing=
    for allow in allow.mount allow.mount.devfs allow.mount.fdescfs \
            allow.mount.nullfs allow.mount.tmpfs allow.chflags \
            allow.raw_sockets; do
        if ! jail -c path=/ command=/usr/bin/true "$allow=true"; then
            missing="${missing:+$missing }$allow"
        fi
    done
    if [ -n "$missing" ]; then
        die "inside a jail that lacks the parameters a container build needs:" \
            "$missing (add them to the jail's configuration)"
    fi

    if ! jail -c path=/ command=/usr/bin/true allow.mount.zfs=true; then
        log "note: no allow.mount.zfs; needed if podman uses the zfs storage driver"
    fi
}

# check_sources reports whether the trees the build uses are there and match the
# commits the superproject records. The build copies the worktrees, not the
# pinned commits, so a dirty submodule means the binaries do not match what the
# superproject says.
check_sources() {
    command -v git >/dev/null 2>&1 ||
        { log "note: git is not installed, cannot check the submodules"; return 0; }
    project=$(git -C "$here" rev-parse --show-toplevel 2>/dev/null) || project=
    [ -n "$project" ] && [ -f "$project/.gitmodules" ] ||
        { log "note: $here is not inside a git checkout, cannot check the submodules"; return 0; }

    missing=
    for path in $(git -C "$project" config --file "$project/.gitmodules" --get-regexp '^submodule\..*\.path$' | awk '{ print $2 }'); do
        state=$(git -C "$project" submodule status -- "$path" 2>/dev/null | cut -c1)
        case "$state" in
        -) missing="${missing:+$missing }$path" ;;
        U) die "$path has a conflict, resolve it before building" ;;
        +) log "warning: $path is not at the commit $project records" ;;
        esac
        if [ -n "$(git -C "$project/$path" status --porcelain --untracked-files=no 2>/dev/null)" ]; then
            log "warning: $path has uncommitted changes, they are what gets built"
        fi
    done
    [ -z "$missing" ] ||
        die "submodule(s) not checked out: $missing (run: git submodule update --init --recursive)"
}

check_podman() {
    command -v podman >/dev/null 2>&1 || \
        die "podman not found (pkg install podman)"
}

check_distfiles() {
    for archive in main.zip v2.3.2.zip include.zip; do
        [ -f "ocijail/distfiles/$archive" ] || \
            die "ocijail/distfiles/$archive missing (run ./prefetch-deps.sh)"
    done
}

bootstrap_rootfs() {
    bootstrap_cid="$(podman create --pull=always --quiet --network=none "$BASE_IMAGE")"
    root="$(podman mount "$bootstrap_cid")"
    [ -n "$root" ] && [ -d "$root" ] ||
        die "cannot mount $bootstrap_cid (with the zfs storage driver the" \
            "jail needs allow.mount.zfs)"

    # pkg inside the bootstrap rootfs needs working resolvers.
    cp /etc/resolv.conf "$root/etc/resolv.conf"

    bootstrapped=1
    log "bootstrap rootfs at $root"
}

prepare_jail() {
    jail_opt="persist ip4=inherit ip6=inherit"
    if [ "$(sysctl -n security.jail.jailed)" != 1 ]; then
        jail_opt="$jail_opt mount.devfs"
    else
        mount -t devfs devfs "$root/dev"
    fi
    bootstrap_jid="$(jail -ci path="$root" $jail_opt)"
    pkg -j "$bootstrap_jid" install -y bazel5 bash go gpgme gmake gnugrep pkgconf

    log "bootstrap jail $bootstrap_jid ready on $root"
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
    jexec "$bootstrap_jid" /bin/sh -c "rm -f $src/bin/podman"

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
    jexec "$bootstrap_jid" /usr/bin/env \
        HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
        /bin/sh -c "cd $src && gmake podman $make_flags"
    jexec "$bootstrap_jid" /bin/sh -c \
        "cp $src/bin/podman /root/podman"
    # Without the cni tag podman only has the netavark backend, which does not
    # exist on FreeBSD, and every network setup fails at run time with
    # "could not find netavark". The tags end up in the binary, so check them.
    jexec "$bootstrap_jid" go version -m /root/podman |
        grep -q -- '-tags=[^[:space:]]*cni' ||
        die "podman was built without the cni tag: $(jexec "$bootstrap_jid" go version -m /root/podman | grep -- '-tags=' || true)"
    mkdir -p "$here/bin"
    install -m 755 "$root/root/podman" "$here/bin/podman"
    log "built $here/bin/podman"
}

build_ocijail() {
    src=/usr/src/ocijail

    jexec "$bootstrap_jid" /usr/bin/env \
        HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
        /bin/sh -c "cd $src/ocijail && exec bazel build --workspace_status_command=/usr/bin/true //ocijail:ocijail"
    jexec "$bootstrap_jid" /bin/sh -c \
        "cp $src/bazel-bin/ocijail/ocijail /root/ocijail"
    [ -f "$root/root/ocijail" ] ||
        die "bazel did not produce $src/bazel-bin/ocijail/ocijail"
    mkdir -p "$here/bin"
    install -m 755 "$root/root/ocijail" "$here/bin/ocijail"
    log "built $here/bin/ocijail"
}

build_ngbridge() {
    src=/usr/src/ngbridge

    jexec "$bootstrap_jid" /usr/bin/env \
        HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
        /bin/sh -c "cd $src && \
            go build -o ngbridge . && \
            go build -o ngbridge-cni ./cmd/ngbridge-cni && \
            go build -o netavark ./cmd/ngbridge-netavark"
    mkdir -p "$here/bin"
    install -m 755 "$root/usr/src/ngbridge/ngbridge" "$here/bin/ngbridge"
    install -m 755 "$root/usr/src/ngbridge/ngbridge-cni" "$here/bin/ngbridge-cni"
    install -m 755 "$root/usr/src/ngbridge/netavark" "$here/bin/netavark"
    log "built $here/bin/ngbridge"
}

build_image() {
    # The image only adds the binary built above; its RUN step needs a runtime,
    # which is the one we just built unless RUNTIME says otherwise. Its base
    # image comes from the Containerfile's ARG, not from BASE_IMAGE.
    podman \
        --runtime "$here/bin/ocijail" build --network host \
        -f "$here/Containerfile" -t "$BUILD_IMAGE" "$here"
    log "built image $BUILD_IMAGE"
}

check_os
check_chroot
check_jail
check_sources
check_podman
check_distfiles

log "bootstrap base image $BASE_IMAGE"
bootstrap_rootfs
prepare_jail
copy_sources
use_container_libs
build_podman
build_ocijail
build_ngbridge

if [ -n "$BUILD_IMAGE" ]; then
    build_image

    # The command goes to stdout, unprefixed, so it can be copied; everything else
    # this script says is on stderr.
    log "run the image with:"
    cat <<EOT
podman \\
    --runtime "$here/bin/ocijail" \\
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
    $BUILD_IMAGE
EOT
fi

local_network="$(CONTAINERS_HELPER_BINARY_DIR="$here/bin" "$here/bin/podman" info --format '{{.Host.NetworkBackend}}')"
system_network="$local_network"

if system_podman="$(command -v podman)"; then
    system_network="$("$system_podman" info --format '{{.Host.NetworkBackend}}')"
    echo -n "system podman: "
    "$system_podman" --version
    echo "system network: $system_network"
fi

echo -n "local podman: "
"$here/bin/podman" --version
echo "local network: $local_network"
