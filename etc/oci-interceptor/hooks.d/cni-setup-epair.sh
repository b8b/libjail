set -e

host_if="$(ifconfig epair create description "associated with jail: {{ env.CNI_CONTAINERID }}")"
trap "ifconfig $host_if destroy" EXIT

jail_if="${host_if%[ab]}b"

ifconfig "$jail_if" vnet '{{ env.CNI_CONTAINERID }}'

if [ "$jail_if" != '{{ env.CNI_IFNAME }}' ]; then
  jail_if="$(ifconfig -j '{{ env.CNI_CONTAINERID }}' "$jail_if" name '{{ env.CNI_IFNAME }}')"
fi

ifconfig "$host_if" up

{% if cniConfig.bridge is defined %}
  ifconfig '{{ cniConfig.bridge }}' addm "$host_if"
{% endif %}

trap '' EXIT
