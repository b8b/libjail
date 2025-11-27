{% for ip in ifConfig.ips|default([]) %}

{% if cniConfig.ipMasq|default(false) == true %}
pfctl -t cni-nat -T del '{{ ip.address }}'
{% endif %}

{% endfor %}

exit 0
