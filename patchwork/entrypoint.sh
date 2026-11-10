#!/bin/sh
set -eu

sysctl -q net.inet.ip.forwarding=1 >/dev/null 2>&1 || true
sysctl -q net.inet6.ip6.forwarding=1 >/dev/null 2>&1 || true
sysctl -q net.pf.filter_local=1 >/dev/null 2>&1 || true

if [ -c /dev/pf ]; then
    egress="${EGRESS_IFACE:-}"
    if [ -z "$egress" ] && default_route="$(route -n get default 2>/dev/null)"; then
        egress="$(echo "$default_route" | awk '/interface:/{print $2}')"
    fi
    if [ -n "$egress" ]; then
        if ! pfctl -f /etc/pf.conf -Dv4egress_if="$egress" -Dv6egress_if="$egress"; then
            echo "entrypoint: failed to load /etc/pf.conf" >&2
        else
            pfctl -e >/dev/null 2>&1 || true
        fi
    fi
elif [ -n "$EGRESS_IFACE" ]; then
    echo "entrypoint: /dev/pf is missing; the devfs ruleset of the jail that" \
         "runs this container has to expose it" >&2
fi

exec "$@"
