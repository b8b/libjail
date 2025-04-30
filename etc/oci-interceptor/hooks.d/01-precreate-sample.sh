{% if oci.annotations.sample|default(false) == true %}
echo hello hook
exit 1
{% else %}
echo sample annotation not present. try to run with "--annotation sample=true"
exit 2
{% endif %}
