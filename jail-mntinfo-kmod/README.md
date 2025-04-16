# jail-mntinfo-kmod

## building

    mkdir -p freebsd14-amd64/work
    cp jail-mntinfo-kmod/* freebsd14-amd64/work/
    
    ./bin/jpkg -r freebsd14-amd64 \
      --exec-stop "cd /work && make" \
      install -y \
      FreeBSD-{runtime,utilities,rc,clang,lld,utilities,clang-dev} \
      FreeBSD-{clibs-dev,runtime-dev,utilities-dev} \
      FreeBSD-{libexecinfo-dev,libcompiler_rt-dev,elftoolchain,libbsm-dev} \
      FreeBSD-{openssl-lib-dev,tcpd-dev,mtree,fetch} FreeBSD-{src,src-sys}
    
    install freebsd14-amd64/work/jail_mntinfo.ko /boot/modules/
    kldxref /boot/modules
    kldload jail_mntinfo
