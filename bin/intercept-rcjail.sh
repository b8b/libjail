#!/bin/sh

# resolve links - $0 may be a softlink
PRG="$0"
RPRG="$(realpath "$PRG")"
BINDIR="${RPRG%/*}"
ROOTDIR="${BINDIR%/*}"

export INTERCEPT_OCI_RUNTIME_NAME=rcjail

script_file="$BINDIR"/intercept-oci-runtime.sh
. "$script_file"
exit 2
