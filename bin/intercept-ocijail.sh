#!/bin/sh

# resolve links - $0 may be a softlink
PRG="$0"
RPRG="$(realpath "$PRG")"
BINDIR="${RPRG%/*}"
ROOTDIR="${BINDIR%/*}"

if [ -z "$INTERCEPT_OCI_RUNTIME_BIN" ]; then
  if ! runtime_bin="$(command -v ocijail)"; then
    export INTERCEPT_OCI_RUNTIME_BIN="/usr/local/bin/ocijail"
  else
    export INTERCEPT_OCI_RUNTIME_BIN="$runtime_bin"
  fi
fi

export INTERCEPT_OCI_RUNTIME_NAME=ocijail
export INTERCEPT_RC_JAIL="$BINDIR"/intercept-rcjail.sh
export INTERCEPT_RC_JAIL_JAVA_CMD="$java_cmd"

script_file="$BINDIR"/intercept-oci-runtime.sh
. "$script_file"
exit 2
