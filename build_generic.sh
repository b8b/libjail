#!/bin/sh

set -e

if [ -e target/libjail ]; then
  echo "target/libjail already exists. remove to run a fresh build!" >&2
  exit 1
fi

./build-scripts/src/main/kotlin/build.kt

read -r VERSION < target/libjail/VERSION

target/libjail/bin/java \
        -XX:AOTMode=record -XX:AOTConfiguration=target/libjail/app.aotconf \
        --enable-native-access=com.github.ajalt.mordant.ffm \
        --enable-native-access=org.cikit.libjail \
        -m org.cikit.oci.interceptor/org.cikit.oci.GenericInterceptor -h

target/libjail/bin/java \
        -XX:AOTMode=create -XX:AOTConfiguration=target/libjail/app.aotconf \
        -XX:AOTCache=target/libjail/app.aot \
        --enable-native-access=com.github.ajalt.mordant.ffm \
        --enable-native-access=org.cikit.libjail \
        -m org.cikit.oci.interceptor/org.cikit.oci.GenericInterceptor -h

cp LICENSE target/libjail/

bsdtar -C target -acf libjail-"$VERSION".tar.zst libjail
