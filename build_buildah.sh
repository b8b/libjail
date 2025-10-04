#!/bin/sh

set -e

mkdir -p ports

./bin/pkgbuild.sh --from scratch --mount ports:/usr/ports \
  -P install -y \
      FreeBSD-runtime FreeBSD-caroot FreeBSD-zoneinfo FreeBSD-openssl \
  --then install -y \
      FreeBSD-certctl \
      FreeBSD-runtime FreeBSD-utilities FreeBSD-rc FreeBSD-mtree \
      FreeBSD-fetch FreeBSD-clang FreeBSD-lld FreeBSD-elftoolchain \
      FreeBSD-clang-dev FreeBSD-clibs-dev FreeBSD-runtime-dev \
      FreeBSD-utilities-dev FreeBSD-libexecinfo-dev \
      FreeBSD-libcompiler_rt-dev FreeBSD-libbsm-dev FreeBSD-openssl-lib-dev \
      FreeBSD-tcpd-dev git \
  --then run --shell "[ -d /usr/ports/.git ] || git clone https://git.freebsd.org/ports.git /usr/ports" \
  --then run --shell "cd /usr/ports && git checkout dbab074be07d75e70541a16ca68ad2921191d38a" \
  --then build-package sysutils/buildah
