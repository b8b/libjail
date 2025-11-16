{% for ip in ipConfig.ips|default([]) %}

set -e

{% if ip.primary %}
{% set options = 'up' %}
{% else %}
{% set options = 'alias' %}
{% endif %}

{% if ip.version == 'Inet4' %}
ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' inet '{{ ip.address }}/{{ ip.prefixLen }}' {{ options }}
{% elif ip.version == 'Inet6' %}
ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' inet6 '{{ ip.address }}/{{ ip.prefixLen }}' {{ options }}
{% endif %}

{% if cniConfig.ipMasq|default(false) == true %}
pfctl -t cni-nat -T add '{{ ip.address }}'
{% endif %}

{% endfor %}

{% for route in ipConfig.routes|default([]) %}

set -e

{% if route.version == 'Inet4' %}
route -j '{{ env.CNI_CONTAINERID }}' add -4 -net '{{ route.dst }}' '{{ route.gw }}'
{% elif route.version == 'Inet6' %}
route -j '{{ env.CNI_CONTAINERID }}' add -6 -net '{{ route.dst }}' '{{ route.gw }}'
{% endif %}

{% endfor %}
