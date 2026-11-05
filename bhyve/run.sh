#!/bin/sh
set -eu

egress="${EGRESS_IFACE:-}"
gateway="${EGRESS_GATEWAY:-}"
if [ -z "$egress" ] || [ -z "$gateway" ]; then
    default_route="$(route -n get default 2>/dev/null || true)"
    if [ -z "$egress" ]; then
        egress="$(printf '%s\n' "$default_route" | awk '/interface:/{print $2}')"
    fi
    if [ -z "$gateway" ]; then
        gateway="$(printf '%s\n' "$default_route" | awk '/gateway:/{print $2}')"
    fi
fi
: "${egress:=eth0}"

vm_name="${VM_NAME:-$(hostname)}"
vm_if_config="$(ifconfig -f inet:cidr -f ether:colon "$egress")"
vm_mac="${vm_if_config#*[[:space:]]ether[[:space:]]}"
vm_mac="${vm_mac%%[[:space:]]*}"
vm_cidr="$(printf '%s\n' "$vm_if_config" |
    awk '/[[:space:]]inet[[:space:]]/ { sub(/.*inet[[:space:]]+/, ""); print $1; exit }')"
vm_prefix="${vm_cidr##*/}"
vm_gateway="$gateway"
vm_ifconfig="DHCP"
[ -z "$vm_cidr" ] || vm_ifconfig="inet ${vm_cidr}"
vm_routes=
if [ -n "$vm_gateway" ] && [ "$vm_prefix" = 32 ]; then
    vm_iface="${GUEST_IFACE:-vtnet0}"
    vm_routes="static_routes=\"gateway\"
route_gateway=\"-host ${vm_gateway} -interface ${vm_iface}\""
fi
vm_console=

if [ -t 0 ]; then
  vm_console="-l com1,stdio"
fi

# setup esp
esp_offset="$((1024 * 1024))"
esp_size="$((1024 * 1024 * ${ESP_SIZE_MIB:-64}))"
esp_root=/disk0

mkdir -p "$esp_root"/boot/loader.conf.d
echo 'vfs.root.mountfrom="zfs:zroot/ROOT/default"' > \
    "$esp_root"/boot/loader.conf.d/rootdev.conf

if ! [ -e disk0.img ]; then
    mkimg -s gpt \
      -p "efi/efiboot0::$esp_size:$esp_offset" \
      -o disk0.img
fi
espadm --image disk0.img --source "$esp_root"

# setup root disk
cat > /disk1/etc/rc.conf <<EOF
hostname="$(hostname)"
ifconfig_DEFAULT="${vm_ifconfig}"
defaultrouter="${vm_gateway}"
zfs_enable="YES"
EOF

mkdir -p /disk1/etc/rc.conf.d

if [ -n "$vm_routes" ]; then
    echo "$vm_routes" > /disk1/etc/rc.conf.d/vmroutes.conf
fi

if [ -n "${AUTHORIZED_KEYS:-}" ]; then
    echo "$AUTHORIZED_KEYS" > /disk1/home/freebsd/.ssh/authorized_keys
    echo 'sshd_enable="YES"' > /disk1/etc/rc.conf.d/sshd.conf
fi

disk1_src_args="/disk1"

if [ -e /disk1.deleted.mtree ]; then
    disk1_src_args="/disk1.deleted.mtree /disk1"
fi

makefs -t zfs -s "${ROOT_SIZE:-100g}" -D -N /etc \
    -o poolname=zroot \
    -o rootpath=/ \
    -o bootfs=zroot/ROOT/default \
    -o 'fs=zroot;mountpoint=none' \
    -o 'fs=zroot/ROOT;mountpoint=none' \
    -o 'fs=zroot/ROOT/default;mountpoint=/;canmount=noauto;compression=on' \
    /disk1.img ${disk1_src_args}

set -x

exec /usr/sbin/bhyve -ADHu \
  -m "${MEM_SIZE:-1g}" \
  -c "${NCPU:-2}" \
  -l bootrom,/usr/local/share/uefi-firmware/BHYVE_UEFI.fd \
  ${vm_console} \
  -s 0,hostbridge \
  -s 1:0,virtio-blk,disk0.img \
  -s 1:1,virtio-blk,disk1.img \
  -s 2:0,virtio-net,netgraph,path="$egress":,peerhook=lower,mac="$vm_mac" \
  -s 3,virtio-rnd \
  -s 31,lpc \
  vm-"$vm_name"
