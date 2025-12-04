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

ifconfig -j '{{ env.CNI_CONTAINERID }}' "$jail_if" name '{{ env.CNI_IFNAME }}'

{% if cniArgs.MAC is defined %}

ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' ether '{{ cniArgs.MAC }}'

printf '{"interfaces":[{"name":"%s","mac":{{ cniArgs.MAC|json }}}]}\n' \
  "$jail_if": >> '{{ setupResultFile }}'

printf '{"interfaces":[{"name":{{ env.CNI_IFNAME|json }},"mac":{{ cniArgs.MAC|json }}]}\n' \
  >> '{{ setupResultFile }}'

{% else %}

jail_ifc="$(ifconfig -j '{{ env.CNI_CONTAINERID }}' -D '{{ env.CNI_IFNAME }}')"
jail_mac="${jail_ifc##*[[:space:]]ether[[:space:]]}"
jail_mac="${jail_mac%%[[:space:]]*}"

printf '{"interfaces":[{"name":"%s","mac":"%s"}]}\n' \
  "$jail_if": "$jail_mac" >> '{{ setupResultFile }}'

printf '{"interfaces":[{"name":{{ env.CNI_IFNAME|json }},"mac":"%s"}]}\n' \
  "$jail_mac" >> '{{ setupResultFile }}'

{% endif %}

{% if cniConfig.bridge is defined %}
ifconfig '{{ cniConfig.bridge }}' addm "$host_if"
bridge_ifc="$(ifconfig -D '{{ cniConfig.bridge }}')"
bridge_mac="${bridge_ifc##*[[:space:]]ether[[:space:]]}"
bridge_mac="${bridge_mac%%[[:space:]]*}"
printf '{"interfaces":[{"name":{{ cniConfig.bridge|json }},"mac":"%s"}]}\n' \
  "$bridge_mac" >> '{{ setupResultFile }}'
{% endif %}

trap '' EXIT
