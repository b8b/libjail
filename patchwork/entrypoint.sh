#!/bin/sh
#
# Prepare the container's vnet so the CNI plugins used by the podman inside it
# can NAT container traffic. pf is per-vnet: enabling it here only affects this
# container's network stack, the module itself has to be loaded on the host.
set -eu

egress="${EGRESS_IFACE:-}"
if [ -z "$egress" ]; then
    egress="$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')"
fi
: "${egress:=eth0}"

default_subnet="${NETWORK_SUBNET:-10.89.0.0/16}"
default_network_backend=cni
network_backend="${NETWORK_BACKEND:-$default_network_backend}"

cat > /usr/local/etc/containers/containers.conf <<EOF
[engine]
runtime = "ocijail"
runtime_supports_json = ["ocijail"]

[engine.runtimes]
ocijail = ["/usr/local/bin/ocijail"]

[network]
network_backend = "$network_backend"
default_subnet = "$default_subnet"
EOF

if [ "$network_backend" = "cni" ]; then
    #TODO implement cni-config-gen in ngbridge
    cni_type=bridge
    network="${default_subnet%/*}"
    network="${network%.*}"
    gateway="$network".1
    range_start="$network".21
    range_end="$network".199
    if kldstat -qm netgraph; then
        cni_type=ngbridge-cni
    fi

    mkdir -p /usr/local/etc/cni/net.d
    cat > /usr/local/etc/cni/net.d/podman.conflist <<EOF
{
  "cniVersion": "1.0.0",
  "name": "podman",
  "plugins": [
    {
      "type": "$cni_type",
      "bridge": "cni-podman0",
      "isGateway": true,
      "ipMasq": true,
      "ipam": {
        "type": "host-local",
        "routes": [
          {
            "dst": "0.0.0.0/0"
          }
        ],
        "ranges": [
          [
            {
              "subnet": "$default_subnet",
              "rangeStart": "$range_start",
              "rangeEnd": "$range_end",
              "gateway": "$gateway"
            }
          ]
        ]
      }
    },
    {
      "type": "portmap",
      "capabilities": {
        "portMappings": true
      }
    },
    {
      "type": "firewall",
      "backend": ""
    },
    {
      "type": "tuning"
    }
  ]
}
EOF
fi

cat > /etc/pf.conf <<EOF
# Written by /usr/local/bin/entrypoint.sh
set skip on lo0

table <cni-nat>
nat on $egress inet from <cni-nat> to any -> ($egress)
nat on $egress inet6 from <cni-nat> to !ff00::/8 -> ($egress)

rdr-anchor "cni-rdr/*"
nat-anchor "cni-rdr/*"
EOF

# The bridge plugin turns this on as well; doing it early makes the first
# container work even if a plugin ordering detail changes.
sysctl -q net.inet.ip.forwarding=1 >/dev/null 2>&1 || true
sysctl -q net.inet6.ip6.forwarding=1 >/dev/null 2>&1 || true

if [ ! -c /dev/pf ]; then
    echo "entrypoint: /dev/pf is missing; the devfs ruleset of the jail that" \
         "runs this container has to expose it" >&2
elif ! pfctl -f /etc/pf.conf; then
    echo "entrypoint: failed to load /etc/pf.conf" >&2
else
    pfctl -e >/dev/null 2>&1 || true
fi

exec "$@"
