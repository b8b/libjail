set -e

host_if="$(ifconfig epair create description "associated with jail: {{ env.CNI_CONTAINERID }}")"
trap "ifconfig $host_if destroy" EXIT

ifconfig "$host_if" up

jail_if="${host_if%[ab]}b"

ifconfig "$jail_if" vnet '{{ env.CNI_CONTAINERID }}'

ifconfig -j '{{ env.CNI_CONTAINERID }}' "$jail_if" name '{{ env.CNI_IFNAME }}'

host_ifc="$(ifconfig -D "$host_if")"
host_mac="${host_ifc##*[[:space:]]ether[[:space:]]}"
host_mac="${host_mac%%[[:space:]]*}"

printf '{"interfaces":[{"name":"%s","mac":"%s"}]}\n' \
  "$host_if" "$host_mac" >> '{{ setupResultFile }}'

{% if cniArgs.MAC is defined %}

ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' ether '{{ cniArgs.MAC }}'

printf '{"interfaces":[{"name":{{ env.CNI_IFNAME|json }},"mac":{{ cniArgs.MAC|json }}]}\n' \
  >> '{{ setupResultFile }}'

{% else %}

jail_ifc="$(ifconfig -j '{{ env.CNI_CONTAINERID }}' -D '{{ env.CNI_IFNAME }}')"
jail_mac="${jail_ifc##*[[:space:]]ether[[:space:]]}"
jail_mac="${jail_mac%%[[:space:]]*}"

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
