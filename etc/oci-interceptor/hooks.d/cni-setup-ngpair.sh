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

if ! host_if="$(create_eiface)"; then
  if ! kldstat -qm ng_eiface; then kldload ng_eiface; fi
  host_if="$(create_eiface)"
fi
trap "ngctl shutdown ${host_if}:" EXIT

ifconfig "$host_if" up description "associated with jail: {{ env.CNI_CONTAINERID }}"

jail_if="$(create_eiface)"
trap "ngctl shutdown ${host_if}: || true; ngctl shutdown ${jail_if}:" EXIT
ngctl connect "$host_if": "$jail_if": ether ether

ifconfig "$jail_if" vnet '{{ env.CNI_CONTAINERID }}'

ifconfig -j '{{ env.CNI_CONTAINERID }}' "$jail_if" name '{{ env.CNI_IFNAME }}' > /dev/null

{% if cniArgs.MAC is defined %}
ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' ether '{{ cniArgs.MAC }}' > /dev/null
{% endif %}

{% if cniConfig.bridge is defined %}
  ifconfig '{{ cniConfig.bridge }}' addm "$host_if"
{% endif %}

trap '' EXIT
