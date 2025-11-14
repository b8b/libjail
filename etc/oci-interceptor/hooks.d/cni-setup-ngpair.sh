set -e

#+ mkpeer . eiface ether ether
#+ show .
#  Name: ngctl84495      Type: socket          ID: 00000090   Num hooks: 1
#  Local hook      Peer name       Peer type    Peer ID         Peer hook
#  ----------      ---------       ---------    -------         ---------
#  ether           ngeth10         eiface       00000091        ether

create_eiface()
{
  local ngout="$(
ngctl -f - << __EOF__
mkpeer . eiface ether ether
show .
__EOF__
)"
  local field
  for field in $ngout; do
    case "$field" in
    ngeth[0-9]*)
      echo "$field"
      break
      ;;
    esac
  done
}

iface_a="$(create_eiface)"
trap 'ngctl shutdown "$iface_a":' EXIT

echo "created $iface_a" >&2

iface_b="$(create_eiface)"
trap 'ngctl shutdown "$iface_a":; ngctl shutdown "$iface_b":' EXIT

echo "created $iface_b" >&2

set -x

ngctl connect "$iface_a": "$iface_b": ether ether

ifconfig "$iface_a" up description "associated with jail: {{ env.CNI_CONTAINERID }}"

ifconfig "$iface_b" vnet "{{ env.CNI_CONTAINERID }}"
ifconfig -j "{{ env.CNI_CONTAINERID }}" "$iface_b" name "{{ env.CNI_IFNAME }}" > /dev/null

{% if cniConfig.bridge is defined %}
  ifconfig "{{ cniConfig.bridge }}" addm "$iface_a"
{% endif %}

trap '' EXIT
