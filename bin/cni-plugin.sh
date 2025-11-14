#!/bin/sh

# resolve links - $0 may be a softlink
PRG="$0"
RPRG="$(realpath "$PRG")"
BINDIR="${RPRG%/*}"
ROOTDIR="${BINDIR%/*}"

if [ -z "$java_cmd" ]; then
  java_cmd="$INTERCEPT_RC_JAIL_JAVA_CMD"
fi

export INTERCEPT_OCI_RUNTIME_NAME=cni

script_file="$BINDIR"/intercept-oci-runtime.sh
. "$script_file"
exit 2
