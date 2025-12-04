set -e

{% if cniConfig.type == "ngbridge" %}

{% set jailInterface = ifConfig.interfaces|selectattr("name", "eq", env.CNI_IFNAME)|first %}
{% set jailMac = jailInterface.mac %}

{% macro ngNodes -%}
  {%- for interface in ifConfig.interfaces -%}
    {%- if interface.name|matches_regex("ngeth.*:") -%}
      {{- interface.name -}},
    {%- endif -%}
  {%- endfor -%}
{% endmacro %}
{% set bpfNode = "bpf" + ngNodes()|regex_replace(",.*", "")|trim %}

{% macro hostMacs %}
  {%- for interface in ifConfig.interfaces -%}
    {%- if interface.sandbox|default(null) == null and interface.name|endswith(":") == false -%}
      {{- interface.mac -}},
    {%- endif -%}
  {%- endfor -%}
{% endmacro %}
{% set hostMac = hostMacs()|regex_replace(",.*", "")|trim %}

{% macro jail2bridge %}
  {{- "ether src " + jailMac + " and (0=1" -}}
  {%- for ip in ifConfig.ips|default([]) -%}
    {{- " or src host " + ip.address -}}
  {%- endfor -%}
  {{- ")" -}}
  {%- if cniConfig.isForceGateway|default(false) -%}
    {{- " and (ether dst " + hostMac -}}
    {%- for ip in ifConfig.ips|default([]) -%}
      {%- if ip.gateway|default(null) != null -%}
        {#- required for arp -#}
        {{- " or dst host " + ip.gateway -}}
      {%- endif -%}
    {%- endfor -%}
    {{- ")" -}}
  {%- elif cniConfig.isRestrictMulticast|default(false) -%}
    {{- " and (ether broadcast or not ether multicast or (0=1" -}}
    {%- for route in ifConfig.routes|default([])|selectattr("isMulticast", "eq", true) -%}
      {{- " or dst net " + route.dst -}}
    {%- endfor -%}
    {{- "))" -}}
  {%- endif -%}
{% endmacro %}

{% macro bridge2jail -%}
  {{- "ether src " + hostMac -}}
  {% for ip in ifConfig.ips|default([]) %}
    {%- if ip.network|default(null) != null -%}
      {%- for ip in ifConfig.ips|default([]) -%}
        {{- " or dst host " + ip.address -}}
      {%- endfor -%}
    {%- endif -%}
  {%- endfor -%}
  {%- if cniConfig.isRestrictMulticast|default(false) -%}
    {%- for route in ifConfig.routes|default([])|selectattr("isMulticast", "eq", true) -%}
      {{- " or dst net " + route.dst -}}
    {%- endfor -%}
  {%- else -%}
    {{- " or ip multicast" -}}
  {%- endif -%}
{% endmacro %}

bpf_prog()
{
   local PATTERN="$1"
   echo "compiling bpf expression '$PATTERN'" >&2
   local PROG="$(tcpdump -s 8192 -p -ddd -y EN10MB "$PATTERN")"
   (
       read len
       echo -n "bpf_prog_len=$len "
       echo -n "bpf_prog=["
       while read code jt jf k ; do
           echo -n " { code=$code jt=$jt jf=$jf k=$k }"
       done
       echo " ]"
   ) << __EOF__
$PROG
__EOF__
}

# jail -> bridge filter
prog="$(bpf_prog "{{ jail2bridge() }}")"
ngctl msg '{{ bpfNode }}' setprogram \
  "{ thisHook=\"jail\" ifMatch=\"bridge\" ifNotMatch=\"debug\" $prog }"

# bridge -> jail filter
prog="$(bpf_prog "{{ bridge2jail() }}")"
ngctl msg '{{ bpfNode }}' setprogram \
  "{ thisHook=\"bridge\" ifMatch=\"jail\" ifNotMatch=\"debug\" $prog }"

{% endif %}

{% macro hostIfs %}
  {%- for interface in ifConfig.interfaces -%}
    {%- if interface.sandbox|default(null) == null and interface.name|endswith(":") == false -%}
      {{- interface.name -}},
    {%- endif -%}
  {%- endfor -%}
{% endmacro %}
{% set hostIf = hostIfs()|regex_replace(",.*", "")|trim %}

routes_added='|'

route_once()
{
  local dst="$1"
  shift
  case "$routes_added" in
  *"|$dst|"*)
    ;;
  *)
    route "$@"
    routes_added="$routes_added$dst|"
    ;;
  esac
}

{% for ip in ifConfig.ips|default([]) %}

  {% if ip.ipVersion == 'IPV4' %}
    {% set ip_version_ifconfig = "inet" %}
    {% set ip_version_arg = "-4" %}
  {% elif ip.ipVersion == 'IPV6' %}
    {% set ip_version_ifconfig = "inet6" %}
    {% set ip_version_arg = "-6" %}
  {% else %}
    {% set ip_version_ifconfig = "" %}
    {% set ip_version_arg = "" %}
  {% endif %}

  {% if ip.gateway|default(null) != null %}
#FIXME this clears the arp cache and host routes - should only run if needed
ifconfig '{{ hostIf }}' '{{ ip_version_ifconfig }}' '{{ ip.gateway }}/{{ ip.prefixLen }}' alias
  {% endif %}

  {% if cniConfig.isForceGateway|default(false) %}

    {% if ip.ipVersion == 'IPV4' %}
ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' inet '{{ ip.address }}/32' alias
    {% elif ip.ipVersion == 'IPV6' %}
ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' inet6 '{{ ip.address }}/128' alias
    {% endif %}

    {% if ip.gateway|default(null) != null and ip.network|default(null) != null %}
route_once '{{ ip.gateway }}' -j '{{ env.CNI_CONTAINERID }}' \
  add {{ ip_version_arg }} -host '{{ ip.gateway }}' -interface '{{ env.CNI_IFNAME }}'
route_once '{{ ip.network }}' -j '{{ env.CNI_CONTAINERID }}' \
  add {{ ip_version_arg}} -net '{{ ip.network }}' '{{ ip.gateway }}'
    {% endif %}

  {% else %}

ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' \
  {{ ip_version_ifconfig }} '{{ ip.address }}/{{ ip.prefixLen }}' alias

  {% endif %}

  {% if cniConfig.ipMasq|default(false) == true %}
pfctl -t cni-nat -T add '{{ ip.address }}'
  {% endif %}

{% endfor %}

{% for route in ifConfig.routes|default([]) %}

  {% if route.ipVersion == 'IPV4' %}
    {% set ip_version_arg = "-4" %}
  {% elif route.ipVersion == 'IPV6' %}
    {% set ip_version_arg = "-6" %}
  {% else %}
    {% set ip_version_arg = "" %}
  {% endif %}

  {% if route.gateway|default(null) != null %}
    {% if route.isFarGateway or cniConfig.isForceGateway|default(false) %}
route_once '{{ route.gateway }}' -j '{{ env.CNI_CONTAINERID }}' \
  add {{ ip_version_arg }} -host '{{ route.gateway }}' -interface '{{ env.CNI_IFNAME }}'
    {% endif %}
route_once '{{ route.dst }}' -j '{{ env.CNI_CONTAINERID }}' \
  add {{ ip_version_arg }} -net '{{ route.dst }}' '{{ route.gateway }}'
  {% else %}
route_once '{{ route.dst }}' -j '{{ env.CNI_CONTAINERID }}' \
  add {{ ip_version_arg }} -net '{{ route.dst }}' -interface '{{ env.CNI_IFNAME }}'
  {% endif %}
{% endfor %}
