#!/bin/sh
set -eu

here=$(realpath "$(dirname "$0")")
tree="$here"
cd "$tree"

log() { echo "build.sh: $*" >&2; }
die() { echo "build.sh: $*" >&2; exit 1; }

bootstrap_cid=
bootstrap_jid=
jail_root=

on_exit() {
    status="$?"
    if [ -n "$bootstrap_jid" ]; then
        jail -r "$bootstrap_jid"
        umount -f "$jail_root/dev"
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

check_distfiles() {
    for archive in main.zip v2.3.2.zip include.zip; do
        [ -f "ocijail/distfiles/$archive" ] || \
            die "ocijail/distfiles/$archive missing (run ./prefetch-deps.sh)"
    done
}

bootstrap_rootfs() {
    bootstrap_cid="$(podman create --quiet --network=none freebsd-minimal)"
    jail_root="$(podman mount "$bootstrap_cid")"
    [ -n "$jail_root" ] && [ -d "$jail_root" ] ||
        die "cannot mount $bootstrap_cid (with the zfs storage driver the" \
            "jail needs allow.mount.zfs)"

    # pkg inside the bootstrap rootfs needs working resolvers.
    cp /etc/resolv.conf "$jail_root/etc/resolv.conf"

    bootstrapped=1
    log "bootstrap rootfs at $jail_root"
}

prepare_jail() {
    jail_opt="persist allow.chflags ip4=inherit ip6=inherit"
    if [ "$(sysctl -n security.jail.jailed)" != 1 ]; then
        jail_opt="$jail_opt mount.devfs"
    else
        mount -t devfs devfs "$jail_root/dev"
    fi
    bootstrap_jid="$(jail -ci path="$jail_root" $jail_opt)"
    pkg -j "$bootstrap_jid" install -y \
        FreeBSD-set-devel bazel5 bash go gpgme gmake gnugrep pkgconf

    log "bootstrap jail $bootstrap_jid ready on $jail_root"
}

copy_sources() {
    src="$jail_root/usr/src"
    mkdir -p "$src"/ocijail
    cp -a podman "$src/"
    cp -a container-libs "$src/"
    cp -a ocijail/WORKSPACE ocijail/BUILD ocijail/.bazelrc ocijail/ocijail ocijail/third_party ocijail/distfiles ocijail/tools "$src/ocijail/"
    cp -a ngbridge "$src/"
    log "sources copied to $src"
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

    if [ -n "${PODMAN_REVENDOR:-}" ]; then
        jexec "$bootstrap_jid" /usr/bin/env \
            HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
            GOTOOLCHAIN=local sh -c "cd $src && \
                go mod edit -replace common=../container-libs/common && \
                go mod edit -replace storage=../container-libs/storage && \
                go mod edit -replace image=../container-libs/image && \
                go mod vendor
            " || die "vendoring container-libs into $src failed"
    fi

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
    install -m 755 "$jail_root/root/podman" "$here/bin/podman"
    log "built $here/bin/podman"
}

build_ocijail() {
    src=/usr/src/ocijail

    jexec "$bootstrap_jid" /usr/bin/env \
        HOME=/root PATH=/usr/local/bin:/usr/bin:/bin \
        /bin/sh -c "cd $src/ocijail && exec bazel build --workspace_status_command=/usr/bin/true //ocijail:ocijail"
    jexec "$bootstrap_jid" /bin/sh -c \
        "cp $src/bazel-bin/ocijail/ocijail /root/ocijail"
    [ -f "$jail_root/root/ocijail" ] ||
        die "bazel did not produce $src/bazel-bin/ocijail/ocijail"
    mkdir -p "$here/bin"
    install -m 755 "$jail_root/root/ocijail" "$here/bin/ocijail"
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
    install -m 755 "$jail_root/usr/src/ngbridge/ngbridge" "$here/bin/ngbridge"
    install -m 755 "$jail_root/usr/src/ngbridge/ngbridge-cni" "$here/bin/ngbridge-cni"
    install -m 755 "$jail_root/usr/src/ngbridge/netavark" "$here/bin/netavark"
    log "built $here/bin/ngbridge"
}

check_sources
check_distfiles

bootstrap_rootfs
prepare_jail
copy_sources
build_podman
build_ocijail
build_ngbridge

