set -e

create_eiface()
{
  local name=""
  set -- $(ngctl -f - << __EOF__
mkpeer . eiface new_eiface ether
show -n .:new_eiface
__EOF__
)
  while [ "$#" -gt 0 ]; do
    case "$1" in
    [Nn][Aa][Mm][Ee]:)
      shift
      name="$1"
      break
      ;;
    esac
    shift
  done
  echo "$name"
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
