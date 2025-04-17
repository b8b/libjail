# jail-mntinfo-kmod

## building

    mkdir -p build-freebsd-`uname -r`-`uname -m`
    
    ./bin/jpkg -r build-freebsd-`uname -r`-`uname -m` \
      --mount jail-mntinfo-kmod:/work \
      --exec-stop "cd /work && make" \
      install -y \
      FreeBSD-{runtime,utilities,rc,clang,lld,utilities,clang-dev} \
      FreeBSD-{clibs-dev,runtime-dev,utilities-dev} \
      FreeBSD-{libexecinfo-dev,libcompiler_rt-dev,elftoolchain,libbsm-dev} \
      FreeBSD-{openssl-lib-dev,tcpd-dev,mtree,fetch} FreeBSD-{src,src-sys}
    
    install jail-mntinfo-kmod/jail_mntinfo.ko /boot/modules/
    kldxref /boot/modules
    kldload jail_mntinfo
