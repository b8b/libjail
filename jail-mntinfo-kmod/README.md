# jail-mntinfo-kmod

## building

    mkdir -p build-freebsd-`uname -r`-`uname -m`
    
    ../bin/jpkg -P --path build-freebsd-`uname -r`-`uname -m` \
      --mount .:/work \
      install -yg \
      FreeBSD-src FreeBSD-src-sys \
      FreeBSD-runtime FreeBSD-utilities FreeBSD-rc FreeBSD-mtree \
      FreeBSD-fetch FreeBSD-clang FreeBSD-lld FreeBSD-elftoolchain \
      FreeBSD-clang-dev FreeBSD-clibs-dev FreeBSD-runtime-dev \
      FreeBSD-utilities-dev FreeBSD-libexecinfo-dev \
      FreeBSD-libcompiler_rt-dev FreeBSD-libbsm-dev FreeBSD-openssl-lib-dev \
      FreeBSD-tcpd-dev \
      --then run --shell "cd /work && make"
    
    install jail_mntinfo.ko /boot/modules/
    kldxref /boot/modules
    kldload jail_mntinfo
