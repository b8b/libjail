ifconfig -j "{{ env.CNI_CONTAINERID }}" "{{ env.CNI_IFNAME }}" destroy

exit 0
