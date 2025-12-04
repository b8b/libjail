{% for interface in ifConfig.interfaces|default([]) %}
  {% if interface.name|startswith("epair") and interface.sandbox|default(null) == null %}
ifconfig '{{ interface.name }}' destroy
  {% endif %}
{% endfor %}
exit 0
