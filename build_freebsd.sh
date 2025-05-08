#!/bin/sh

set -e

if [ -e target/libjail ]; then
  echo "target/libjail already exists. remove to run a fresh build!" >&2
  exit 1
fi

./bin/jpkg.sh -P --from scratch --mount .:/src \
  install -y \
      FreeBSD-runtime FreeBSD-caroot FreeBSD-zoneinfo FreeBSD-openssl \
  --then install -y \
      FreeBSD-certctl FreeBSD-src FreeBSD-src-sys \
      FreeBSD-runtime FreeBSD-utilities FreeBSD-rc FreeBSD-mtree \
      FreeBSD-fetch FreeBSD-clang FreeBSD-lld FreeBSD-elftoolchain \
      FreeBSD-clang-dev FreeBSD-clibs-dev FreeBSD-runtime-dev \
      FreeBSD-utilities-dev FreeBSD-libexecinfo-dev \
      FreeBSD-libcompiler_rt-dev FreeBSD-libbsm-dev FreeBSD-openssl-lib-dev \
      FreeBSD-tcpd-dev openjdk24 rust \
  --then run --shell 'set -e
      export JAVA_HOME=/usr/local/openjdk24
      cd /src/rust-jail-cleanup && cargo build --release --locked
      cd /src/rust-java-launcher && cargo build --release --locked
      cd /src/jail-mntinfo-kmod && make
      cd /src && ./build-scripts/src/main/kotlin/build.kt
      target/libjail/bin/java \
        -XX:AOTMode=record -XX:AOTConfiguration=target/libjail/app.aotconf \
        --enable-native-access=com.github.ajalt.mordant.ffm \
        --enable-native-access=org.cikit.libjail \
        -m org.cikit.oci.interceptor/org.cikit.oci.jail.JPkgCommand -h
      target/libjail/bin/java \
        -XX:AOTMode=create -XX:AOTConfiguration=target/libjail/app.aotconf \
        -XX:AOTCache=target/libjail/app.aot \
        --enable-native-access=com.github.ajalt.mordant.ffm \
        --enable-native-access=org.cikit.libjail \
        -m org.cikit.oci.interceptor/org.cikit.oci.jail.JPkgCommand -h
      '

read -r VERSION < target/libjail/VERSION

cp LICENSE target/libjail/

cp rust-java-launcher/target/release/rust-java-launcher target/libjail/bin/jpkg
ln -f target/libjail/bin/jpkg target/libjail/bin/intercept-oci-runtime
ln -f target/libjail/bin/jpkg target/libjail/bin/intercept-ocijail
ln -f target/libjail/bin/jpkg target/libjail/bin/intercept-rcjail

mkdir target/libjail/kld
cp jail-mntinfo-kmod/jail_mntinfo.ko target/libjail/kld/

mkdir target/libjail/sbin
cp rust-jail-cleanup/target/release/rust-jail-cleanup target/libjail/sbin/

tar -C target -acf libjail-"$VERSION".tar.zst libjail
