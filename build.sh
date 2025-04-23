#!/bin/sh

set -e

export LIBJAIL_VERSION="${LIBJAIL_VERSION:=0.0.1}"
export JAVA_HOME=/usr/local/openjdk17

./bin/jpkg.sh -P --from scratch --mount .:/src \
  install -y \
      FreeBSD-src FreeBSD-src-sys \
      FreeBSD-runtime FreeBSD-utilities FreeBSD-rc FreeBSD-mtree \
      FreeBSD-fetch FreeBSD-clang FreeBSD-lld FreeBSD-elftoolchain \
      FreeBSD-clang-dev FreeBSD-clibs-dev FreeBSD-runtime-dev \
      FreeBSD-utilities-dev FreeBSD-libexecinfo-dev \
      FreeBSD-libcompiler_rt-dev FreeBSD-libbsm-dev FreeBSD-openssl-lib-dev \
      FreeBSD-tcpd-dev openjdk17 rust \
  --then run --shell "set -e
  cd /src/rust-jail-cleanup && cargo build --release --locked
  cd /src/jail-mntinfo-kmon && make
  cd /src && WORKDIR=/work ./build-scripts/src/main/kotlin/build.kt
  "
