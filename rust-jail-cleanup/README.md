# rust-jail-cleanup

## building

    mkdir -p build-freebsd-`uname -r`-`uname -m`
    
    ./bin/jpkg -r build-freebsd-`uname -r`-`uname -m` \
      --mount rust-jail-cleanup:/root \
      --exec-stop "cd /root && cargo build --release" \
      install -y \
      rust FreeBSD-clang FreeBSD-lld FreeBSD-utilities \
      FreeBSD-clang-dev FreeBSD-clibs-dev FreeBSD-runtime-dev \
      FreeBSD-utilities-dev FreeBSD-libexecinfo-dev FreeBSD-rc

## usage

rust-jail-cleanup -j <JID> <COMMAND>

Commands:
mnt-info      attach to jail and print mount info json (see jail-mntinfo-kmod)
unmount       attach to jail and call unmount(BY_FSID)
destroy-vmm   attach to jail and destroy vmm (via sysctl)

## roadmap

(1) allow the jvm code to use this tool instead of attaching itself
(2) port the complete `intercept-rcjail cleanup` command.
