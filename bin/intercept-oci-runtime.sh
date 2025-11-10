#!/bin/sh

# resolve links - $0 may be a softlink
PRG="$0"
RPRG="$(realpath "$PRG")"
BINDIR="${RPRG%/*}"
ROOTDIR="${BINDIR%/*}"

if [ -z "$M2_LOCAL_REPO" ]; then
  export M2_LOCAL_REPO="$ROOTDIR"/cache/java
fi

if [ -z "$java_cmd" ]; then
  if [ -x "$ROOTDIR"/jre/bin/java ]; then
    java_cmd="$ROOTDIR"/jre/bin/java
  fi
fi

export INTERCEPT_OCI_CONFIG="$ROOTDIR"/etc/containers/oci-interceptor.conf
export INTERCEPT_OCI_TEMPLATES_DIR="$ROOTDIR"/etc/oci-interceptor/hooks.d
export INTERCEPT_OCI_STATE_DIR="$ROOTDIR"/tmp

# __kotlin_script_installer__
#
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=2.2.21.32
p=org/cikit/kotlin_script/"$v"/kotlin_script-"$v".sh
url="${M2_CENTRAL_REPO:=https://repo1.maven.org/maven2}"/"$p"
kotlin_script_sh="${M2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$p"
if ! [ -r "$kotlin_script_sh" ]; then
  kotlin_script_sh="$(mktemp)" || exit 1
  fetch_cmd="$(command -v curl) -kfLSso" || \
    fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" || \
    fetch_cmd="wget --no-check-certificate -qO"
  if ! $fetch_cmd "$kotlin_script_sh" "$url"; then
    echo "failed to fetch kotlin_script.sh from $url" >&2
    rm -f "$kotlin_script_sh"; exit 1
  fi
  dgst_cmd="$(command -v openssl) dgst -sha256 -r" || dgst_cmd=sha256sum
  case "$($dgst_cmd < "$kotlin_script_sh")" in
  "ad53c905302b3247059729f3ff4762727a0c52b903d66241acc277c60d427e94 "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2

///PLUGIN=org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable

///DEP=org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2

///DEP=org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0
///DEP=org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0

///DEP=net.vieiro:toml-java:13.4.2
///DEP=org.antlr:antlr4-runtime:4.13.1

///DEP=org.cikit:forte-jvm:0.6.2
///DEP=org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.8.0

///DEP=com.github.ajalt.clikt:clikt-jvm:5.0.3
///DEP=com.github.ajalt.clikt:clikt-core-jvm:5.0.3
///DEP=com.github.ajalt.mordant:mordant-jvm:3.0.2
///DEP=com.github.ajalt.mordant:mordant-core-jvm:3.0.2
///DEP=com.github.ajalt.colormath:colormath-jvm:3.6.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-jna-jvm:3.0.2
///DEP=net.java.dev.jna:jna:5.15.0
///RDEP=com.github.ajalt.mordant:mordant-jvm-ffm-jvm:3.0.2
///RDEP=com.github.ajalt.mordant:mordant-jvm-graal-ffi-jvm:3.0.2

///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/main.kt

///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/jail/pkgbuild.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/jail/pipeline.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/jail/pkgconfig.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/jail/pkgboot.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/jail/ocijail.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/jail/rcjail.kt

///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/GenericInterceptor.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/InterceptorConfig.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/OciCommand.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/OciConfig.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/OciLogger.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/OciRuntimeCommand.kt

///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/CreateCommand.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/DeleteCommand.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/ExecCommand.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/KillCommand.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/ListCommand.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/StartCommand.kt
///INC=../oci-interceptor/src/main/kotlin/org/cikit/oci/StateCommand.kt

///INC=../libjail/src/main/kotlin/org/cikit/libjail/cleanup.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/jail.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/kill.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/mount.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/sysctl.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/util.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/util_jna.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/vmm.kt
///INC=../libjail/src/main/kotlin/org/cikit/libjail/vnet.kt

///MAIN=org.cikit.oci.MainKt
