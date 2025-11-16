set -e

create_eiface()
{
  local name=""
  set -- $(ngctl -f - << __EOF__
mkpeer . eiface new_eiface ether
show -n .:new_eiface
__EOF__
)
  while [ "$#" -gt 0 ]; do
    case "$1" in
    [Nn][Aa][Mm][Ee]:)
      shift
      name="$1"
      break
      ;;
    esac
    shift
  done
  echo "$name"
}

if ! jail_if="$(create_eiface)"; then
  if ! kldstat -qm ng_eiface; then
    kldload ng_eiface
    jail_if="$(create_eiface)"
  fi
fi
trap "ngctl shutdown ${jail_if}:" EXIT
link=$(("${jail_if##*[^0-9]}" + 100))

ifconfig "$jail_if" vnet '{{ env.CNI_CONTAINERID }}'
ifconfig -j '{{ env.CNI_CONTAINERID }}' "$jail_if" name '{{ env.CNI_IFNAME }}' > /dev/null

{% if cniConfig.bridge|default("")|matches_regex('^[^:]+:$') %}

{# assume bridge at given netgraph node #}
if ngout="$(ngctl connect "$jail_if": '{{ cniConfig.bridge }}' ether link"$link" 2>&1)"; then
  trap '' EXIT
  exit 0
fi

case "$ngout" in
*[Nn][Oo][[:space:]][Ss][Uu][Cc][Hh]*)
    # bridge does not exist
    ;;
*)
  echo "node '{{ cniConfig.bridge }}' refused connection on link$link" >&2
  ngctl show '{{ cniConfig.bridge }}' || true
  exit 1
  ;;
esac

if kldstat -qm ng_bridge; then kldload ng_bridge; fi
ngctl mkpeer "$jail_if": bridge ether link"$link"
ngctl name "$jail_if":ether '{{ cniConfig.bridge|regex_replace(':$', '') }}'
ngctl connect "$jail_if": '{{ cniConfig.bridge }}' ether link"$link"
trap '' EXIT
exit 0

{% elif ":" in cniConfig.bridge|default("") %}

{# assume preconfigured bridge at given netgraph path #}
resolve_bridge()
{
  local id=""
  local ngout
  if ngout="$(ngctl show -n '{{ cniConfig.bridge }}')"; then
    set -- $ngout
    while [ "$#" -gt 0 ]; do
      case "$1" in
      [Ii][Dd]:)
        shift
        id="$1"
        ;;
      [Tt][Yy][Pp][Ee]:)
        shift
        if [ "$1" != "bridge" ]; then
          echo "node '{{ cniConfig.bridge }}' has invalid type '$1'" >&2
          exit 1
        fi
        ;;
      esac
      shift
    done
  fi
  echo "[$id]"
}

ngctl connect "$jail_if": "$(resolve_bridge)": ether link"$link"

{% else %}

{# assume bridge at '{{ cniConfig.bridge }}:' connected to
 # eiface with host interface renamed to '{{ cniConfig.bridge }}'
 # }

# assume everything is setup
if ngout="$(ngctl connect "$jail_if": '{{ cniConfig.bridge }}:' ether link"$link" 2>&1)"; then
  trap '' EXIT
  exit 0
fi

case "$ngout" in
*[Nn][Oo][[:space:]][Ss][Uu][Cc][Hh]*)
    # bridge does not exist
    ;;
*)
  echo "node '{{ cniConfig.bridge }}:' refused connection on link$link" >&2
  ngctl show '{{ cniConfig.bridge }}:' || true
  exit 1
  ;;
esac

# check host interface
if ! driver_name="$(ifconfig -D '{{ cniConfig.bridge }}')"; then
  # host interface does not exist. auto setup eiface + bridge
  if ! kldstat -qm ng_bridge; then kldload ng_bridge; fi
  driver_name="$(create_eiface)"
  trap "ngctl shutdown ${jail_if}: || true;
        ngctl shutdown ${driver_name}:" EXIT
  ngctl mkpeer "$driver_name": bridge ether link1
  ngctl name "$driver_name":ether '{{ cniConfig.bridge }}'
  ifconfig "$driver_name" name '{{ cniConfig.bridge }}'
  ifconfig "$driver_name" up
  ngctl connect "$jail_if": '{{ cniConfig.bridge }}:' ether link"$link"
  trap '' EXIT
  exit 0
fi

# validate host interface
driver_name="${driver_name##*drivername:[[:space:]]}"
driver_name="${driver_name%%[[:space:]]*}"
case "$driver_name" in
ngeth[0-9]*)
  ifconfig "$driver_name" up
  ;;
*)
  type="${driver_name%%[0-9]*}"
  echo "interface '{{ cniConfig.bridge }}' has invalid type '$type'" >&2
  exit 1
  ;;
esac

# host interface is fine. assume it is connected to a bridge
if ngout="$(ngctl connect "$jail_if": "$driver_name":ether ether link"$link" 2>&1)"
  ngctl name "$driver_name":ether '{{ cniConfig.bridge }}'
  trap '' EXIT
  exit 0
fi

case "$ngout" in
*[Nn][Oo][[:space:]][Ss][Uu][Cc][Hh]*)
    # host interface is not connected
    ;;
*)
  echo "node '${driver_name}:ether' refused connection on link$link" >&2
  ngctl show "$driver_name":ether || true
  exit 1
  ;;
esac

# create bridge
if ! kldstat -qm ng_bridge; then kldload ng_bridge; fi
ngctl mkpeer "$driver_name": bridge ether link1
ngctl name "$driver_name":ether '{{ cniConfig.bridge }}'
ngctl connect "$jail_if": '{{ cniConfig.bridge }}:' ether link"$link"
trap '' EXIT

{% endif %}
