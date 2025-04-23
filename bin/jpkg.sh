#!/bin/sh

set -e

# resolve links - $0 may be a softlink
PRG="$0"
RPRG="$(realpath "$PRG")"
BINDIR="${RPRG%/*}"
ROOTDIR="${BINDIR%/*}"

: ${JPKG_CACHE_BASE:="$ROOTDIR"/cache}
: ${JPKG_INSTALL_JRE:=false}
: ${JPKG_SITE:=pkg.FreeBSD.org}

os_reldate="$(sysctl -n kern.osreldate)"
v_maj="${os_reldate%[0-9][0-9][0-9][0-9][0-9]}"
v_min="${os_reldate%[0-9][0-9][0-9]}"
v_min="${v_min#$v_maj}"
v_min="${v_min#0}"
arch="$(sysctl -n hw.machine_arch)"

trusted_keys=/usr/share/keys/pkg/trusted
cache_root=

case "$os_reldate" in
*000)
  cache_root="$JPKG_CACHE_BASE"/FreeBSD-"$v_maj"-"$arch"/r"$v_min"
  ;;
*)
  cache_root="$JPKG_CACHE_BASE"/FreeBSD-"$v_maj"-"$arch"/"$os_reldate"
  ;;
esac

repo_config()
{
  local name="$1"
  local url="$2"
  printf '%s: {
    url: "%s",
    mirror_type: "srv",
    signature_type: "fingerprints",
    fingerprints: "/usr/share/keys/pkg",
    enabled: yes\n}\n' "$name" "$url"
}

fetch_pkg()
{
  local fetch_cmd
  fetch_cmd="$(command -v curl) -kfLSso" || \
    fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" || \
    fetch_cmd="wget --no-check-certificate -qO"

  echo "fetching pkg..." >&2
  mkdir -p "$cache_root"/var/cache/pkg
  $fetch_cmd "$cache_root"/var/cache/pkg/pkg.pkg.sig \
    https://"$JPKG_SITE"/FreeBSD:"$v_maj":"$arch"/latest/Latest/pkg.pkg.sig
  sed -ne '/BEGIN PUBLIC KEY/,/END PUBLIC KEY/p' \
    < "$cache_root"/var/cache/pkg/pkg.pkg.sig \
    > "$cache_root"/var/cache/pkg/pkg.pkg.pem
  sed -e '/SIGNATURE/d' -e '/CERT/,//d' \
    < "$cache_root"/var/cache/pkg/pkg.pkg.sig \
    > "$cache_root"/var/cache/pkg/pkg.pkg.der
  chksum="$(openssl dgst -sha256 < "$cache_root"/var/cache/pkg/pkg.pkg.pem)"
  if ! grep -qr "${chksum##* }" "$trusted_keys"; then
    echo "pkg signature invalid: fingerprint not found in $trusted_keys" >& 2
    exit 1
  fi

  $fetch_cmd "$cache_root"/var/cache/pkg/pkg.pkg \
    https://"$JPKG_SITE"/FreeBSD:"$v_maj":"$arch"/latest/Latest/pkg.pkg
  chksum="$(openssl dgst -sha256 < "$cache_root"/var/cache/pkg/pkg.pkg)"
  echo -n "${chksum##* }" > "$cache_root"/var/cache/pkg/pkg.pkg.sha256
  local output="$(openssl dgst -keyform pem -sha256 \
    -verify "$cache_root"/var/cache/pkg/pkg.pkg.pem \
    -signature "$cache_root"/var/cache/pkg/pkg.pkg.der \
    -binary "$cache_root"/var/cache/pkg/pkg.pkg.sha256 2>&1)"
  if ! [ "$?" -eq 0 ]; then
    echo "$output" >&2
    echo "failed to verify pkg." >&2
    exit 1
  fi

  tar -C "$cache_root" \
    -xf "$cache_root"/var/cache/pkg/pkg.pkg \
    -s '|^/||' /usr/local/sbin/pkg-static
  if ! [ "$?" -eq 0 ]; then
    rm -f "$cache_root"/usr/local/sbin/pkg-static
    exit 1
  fi
}

run_pkg()
{
  local pkg_bin="$cache_root"/usr/local/sbin/pkg-static
  INSTALL_AS_USER=yes \
  PKG_DBDIR="$cache_root"/var/db/pkg \
  PKG_CACHEDIR="$cache_root"/var/cache/pkg \
    "$pkg_bin" \
    -C "$cache_root"/usr/local/etc/pkg.conf \
    -R "$cache_root"/usr/local/etc/pkg/repos \
    "$@"
}

init_pkg()
{
  local pkg_bin="$cache_root"/usr/local/sbin/pkg-static

  mkdir -p "$cache_root"/usr/local/etc/pkg/repos
  echo -n > "$cache_root"/usr/local/etc/pkg.conf
  repo_config "base" \
              "pkg+http://$JPKG_SITE/\${ABI}/base_release_$v_min" \
              > "$cache_root"/usr/local/etc/pkg/repos/base.conf
  repo_config "FreeBSD-latest" \
              "pkg+http://$JPKG_SITE/\${ABI}/latest" \
              > "$cache_root"/usr/local/etc/pkg/repos/FreeBSD-latest.conf

  if ! [ -x "$pkg_bin" ]; then
    fetch_pkg
  fi

  #trigger update and read latest version
  local pkg_latest_version=
  local current_version=
  while read -r line; do
    case "$line" in
    Repository[[:space:]]*:[[:space:]]*FreeBSD-latest[[:space:]]*)
      pkg_latest_version="$current_version"
      ;;
    *:*)
      ;;
    *)
      current_version="$line"
      ;;
    esac
  done << __EOF__
$(run_pkg search -L pkg-name -Q repository -S name --exact pkg)
__EOF__

  if [ -z "$pkg_latest_version" ]; then
    echo "error getting latest pkg version!" >&2
    exit 1
  fi

  local pkg_bin_version=pkg-"$("$pkg_bin" -v)"
  if ! [ "$pkg_bin_version" = "$pkg_latest_version" ]; then
    echo "updating pkg: $pkg_bin_version -> $pkg_latest_version" >&2
    run_pkg fetch --quiet --no-repo-update -r FreeBSD-latest -y pkg
    tar -C "$cache_root" \
        -xf "$cache_root"/var/cache/pkg/"$pkg_latest_version".pkg \
        -s '|^/||' /usr/local/sbin/pkg-static
    if ! [ "$?" -eq 0 ]; then exit 1; fi
  fi
}

run_pkg_search_version()
{
  local pkg_name="$1"
  local pkg_repo="${pkg_name%%/*}"
  if [ "$pkg_name" = "$pkg_repo" ]; then
    pkg_repo=base
  else
    pkg_name="${pkg_name#*/}"
  fi
  run_pkg search --quiet --no-repo-update \
          -r "$pkg_repo" -S name -L pkg-name --exact "$pkg_name"
}

install_jre()
{
  local v

  if ! v="$(run_pkg_search_version FreeBSD-latest/openjdk17-jre)" &&\
     ! v="$(run_pkg_search_version FreeBSD-release/openjdk17-jre)"
  then
    echo "error finding openjdk17-jre in remote repositories" >&2
    exit 1
  fi

  if ! [ -x "$cache_root"/usr/local/"$v"/bin/java ]; then
    echo "updating openjdk17-jre..." >&2
    run_pkg fetch -y openjdk17-jre
    rm -Rf "$cache_root"/usr/local/"$v"
    local tmpdir=usr/local/."$$"."$v"

    mkdir "$cache_root"/"$tmpdir" &&\
    tar -C "$cache_root" \
        -xf "$cache_root"/var/cache/pkg/"$v".pkg \
        -s "|^/usr/local/openjdk17-jre|$tmpdir/$v|" \
        /usr/local/openjdk17-jre &&\
    mv "$cache_root"/"$tmpdir"/"$v" "$cache_root"/usr/local/ &&\
    rmdir "$cache_root"/"$tmpdir"

    if ! [ "$?" -eq 0 ]; then
      echo "error extracting openjdk17-jre" >&2
      rm -Rf "$tmpdir"
      exit 1
    fi
  fi

  java_cmd="$cache_root"/usr/local/"$v"/bin/java
}

init_pkg

if [ -z "$M2_LOCAL_REPO" ]; then
  export M2_LOCAL_REPO="$JPKG_CACHE_BASE"/lib
fi

if [ -z "$java_cmd" ]; then
  if [ "$JPKG_INSTALL_JRE" = "true" ] || ! java_cmd="$(command -v java)"; then
    install_jre
  fi
fi

export INTERCEPT_OCI_RUNTIME_NAME=jpkg
export INTERCEPT_RC_JAIL="$BINDIR"/intercept-rcjail.sh
export INTERCEPT_RC_JAIL_JAVA_CMD="$java_cmd"

export JPKG_PKG_BIN="$cache_root"/usr/local/sbin/pkg-static
export JPKG_CACHE_BASE="$JPKG_CACHE_BASE"

script_file="$BINDIR"/intercept-oci-runtime.sh
. "$script_file"
exit 2
