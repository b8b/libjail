set -e

host_if="$(ifconfig epair create description "associated with jail: {{ env.CNI_CONTAINERID }}")"
trap "ifconfig $host_if destroy" EXIT

ifconfig "$host_if" up

jail_if="${host_if%[ab]}b"

ifconfig "$jail_if" vnet '{{ env.CNI_CONTAINERID }}'

ifconfig -j '{{ env.CNI_CONTAINERID }}' "$jail_if" name '{{ env.CNI_IFNAME }}' > /dev/null

{% if cniArgs.MAC is defined %}
ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' ether '{{ cniArgs.MAC }}' > /dev/null
{% endif %}

{% if cniConfig.bridge is defined %}
  ifconfig '{{ cniConfig.bridge }}' addm "$host_if"
{% endif %}

trap '' EXIT
