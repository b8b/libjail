#!/bin/sh

set -e

# Derive the base-image version from the host base system (kernel release
# date), same as pkgbuild does for the ABI/base repo. This pins an immutable
# freebsd-runtime tag while always tracking the host's major.minor.
: ${FBSD_IMAGE_PREFIX:=ghcr.io/freebsd/freebsd-runtime}
os_rel="$(sysctl -n kern.osreldate)"
fbsd_major="$(( $os_rel / 100000 ))"
fbsd_minor="$(( ($os_rel / 1000) % 100 ))"
BASE_IMAGE="${FBSD_IMAGE_PREFIX}:$fbsd_major.$fbsd_minor"

echo "building from base image: $BASE_IMAGE" >&2

if [ -e target/libjail ]; then
  echo "target/libjail already exists. remove to run a fresh build!" >&2
  exit 1
fi

./bin/pkgbuild.sh -P --from "$BASE_IMAGE" --mount .:/src \
  install -y \
      FreeBSD-runtime FreeBSD-caroot FreeBSD-zoneinfo FreeBSD-openssl \
  --then install -y \
      FreeBSD-certctl FreeBSD-src FreeBSD-src-sys \
      FreeBSD-runtime FreeBSD-utilities FreeBSD-rc FreeBSD-mtree \
      FreeBSD-fetch FreeBSD-clang FreeBSD-lld FreeBSD-elftoolchain \
      FreeBSD-clang-dev FreeBSD-clibs-dev FreeBSD-runtime-dev \
      FreeBSD-utilities-dev FreeBSD-libexecinfo-dev \
      FreeBSD-libcompiler_rt-dev FreeBSD-libbsm-dev FreeBSD-openssl-lib-dev \
      FreeBSD-tcpd-dev openjdk25 rust \
  --then run --shell 'set -e
      export JAVA_HOME=/usr/local/openjdk25
      cd /src/rust-jail-cleanup && cargo build --release --locked
      cd /src/rust-java-launcher && cargo build --release --locked
      cd /src/jail-mntinfo-kmod && make
      cd /src && ./build-scripts/src/main/kotlin/build.kt
      target/libjail/bin/java \
        -XX:AOTMode=record -XX:AOTConfiguration=target/libjail/app.aotconf \
        --enable-native-access=com.github.ajalt.mordant.ffm \
        --enable-native-access=org.cikit.libjail \
        -m org.cikit.oci.interceptor/org.cikit.oci.jail.PkgbuildCommand -h
      target/libjail/bin/java \
        -XX:AOTMode=create -XX:AOTConfiguration=target/libjail/app.aotconf \
        -XX:AOTCache=target/libjail/app.aot \
        --enable-native-access=com.github.ajalt.mordant.ffm \
        --enable-native-access=org.cikit.libjail \
        -m org.cikit.oci.interceptor/org.cikit.oci.jail.PkgbuildCommand -h
      '

read -r VERSION < target/libjail/VERSION

cp LICENSE target/libjail/

cp rust-java-launcher/target/release/rust-java-launcher target/libjail/bin/pkgbuild
ln -f target/libjail/bin/pkgbuild target/libjail/bin/intercept-oci-runtime
ln -f target/libjail/bin/pkgbuild target/libjail/bin/intercept-ocijail
ln -f target/libjail/bin/pkgbuild target/libjail/bin/intercept-rcjail
ln -f target/libjail/bin/pkgbuild target/libjail/bin/cni-plugin

mkdir target/libjail/kld
cp jail-mntinfo-kmod/jail_mntinfo.ko target/libjail/kld/

mkdir target/libjail/sbin
cp rust-jail-cleanup/target/release/rust-jail-cleanup target/libjail/sbin/

tar -C target -acf libjail-"$VERSION".tar.zst libjail
