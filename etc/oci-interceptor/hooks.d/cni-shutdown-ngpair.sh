driver_name="$(ifconfig -D -j "{{ env.CNI_CONTAINERID }}" "{{ env.CNI_IFNAME }}")" || exit 0
driver_name="${driver_name##*drivername:[[:space:]]}"
driver_name="${driver_name%%[[:space:]]*}"

ngctl shutdown "$driver_name":ether
ngctl shutdown "$driver_name":

exit 0
