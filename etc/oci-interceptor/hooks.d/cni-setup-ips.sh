set -e

{% if cniConfig.type == "ngbridge" %}

{% macro jail2bridge %}
  {{- "ether src $jail_mac and (0=1" -}}
  {%- for ip in ifConfig.ips|default([]) -%}
    {{- " or src host " + ip.address -}}
  {%- endfor -%}
  {{- ")" -}}
  {%- if cniConfig.isForceGateway|default(false) -%}
    {{- " and (ether dst $host_mac" -}}
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
  {{- "ether src $host_mac" -}}
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

# get host network interface name
ngout="$(ngctl msg '{{ cniConfig.bridge }}:link0' getifname)"
host_if="${ngout#*[Aa][Rr][Gg][Ss]:[[:space:]]\"}"
host_if="${host_if%%\"*}"
host_ifc="$(ifconfig -D "$host_if")"
host_mac="${host_ifc##*[[:space:]]ether[[:space:]]}"
host_mac="${host_mac%%[[:space:]]*}"

# get jail network interface
jail_ifc="$(ifconfig -j '{{ env.CNI_CONTAINERID }}' -D '{{ env.CNI_IFNAME}}')"
jail_mac="${jail_ifc##*[[:space:]]ether[[:space:]]}"
jail_mac="${jail_mac%%[[:space:]]*}"
bpf_node="${jail_ifc#*[[:space:]]drivername:[[:space:]]}"
bpf_node="bpf${bpf_node%%[[:space:]]*}"

# jail -> bridge filter
prog="$(bpf_prog "{{ jail2bridge() }}")"
ngctl msg "$bpf_node": setprogram \
  "{ thisHook=\"jail\" ifMatch=\"bridge\" ifNotMatch=\"debug\" $prog }"

# bridge -> jail filter
prog="$(bpf_prog "{{ bridge2jail() }}")"
ngctl msg "$bpf_node": setprogram \
  "{ thisHook=\"bridge\" ifMatch=\"jail\" ifNotMatch=\"debug\" $prog }"

{% else %}

host_if='{{ cniConfig.bridge }}'
host_ifc="$(ifconfig -D "$host_if")"
host_mac="${host_ifc##*[[:space:]]ether[[:space:]]}"
host_mac="${host_mac%%[[:space:]]*}"

jail_ifc="$(ifconfig -j '{{ env.CNI_CONTAINERID }}' -D '{{ env.CNI_IFNAME}}')"
jail_mac="${jail_ifc##*[[:space:]]ether[[:space:]]}"
jail_mac="${jail_mac%%[[:space:]]*}"

{% endif %}

printf '{"interfaces":[{"name":"%s","mac":"%s"}]}\n' \
  "$host_if" "$host_mac" \
  >> '{{ setupResultFile }}'

printf '{"interfaces":[{"name":{{ env.CNI_IFNAME|json }},"mac":"%s"}]}\n' \
  "$jail_mac" \
  >> '{{ setupResultFile }}'

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
ifconfig "$host_if" '{{ ip_version_ifconfig }}' '{{ ip.gateway }}/{{ ip.prefixLen }}' alias
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
