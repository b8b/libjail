shutdown_peer()
{
  local path="$1"
  local out
  local id=""
  local type=""
  if out="$(ngctl show -n "$path")"; then
    set -- $out
    while [ "$#" -gt 0 ]; do
      case "$1" in
      [Ii][Dd]:)
        shift
        id="$1"
        ;;
      [Tt][Yy][Pp][Ee]:)
        shift
        type="$1"
        ;;
      esac
      shift
    done
  fi
  case "$type" in
  eiface | bpf)
    ngctl shutdown "[$id]:"
    ;;
  esac
}

{% for interface in ifConfig.interfaces|default([]) %}
  {% if interface.name|endswith(":") %}
shutdown_peer '{{ interface.name }}ether'
ngctl shutdown '{{ interface.name }}'
  {% endif %}
{% endfor %}
exit 0
