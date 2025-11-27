set -e

on_exit=''

create_eiface()
{
  local name=''
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

bpf_prog()
{
   local PATTERN="$1"
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

# check bridge node
if ngout="$(ngctl show -n '{{ cniConfig.bridge }}:' 2>&1)"; then
  case "$ngout" in
  *[[:space:]][Tt][Yy][Pp][Ee]:[[:space:]]bridge[[:space:]]*)
    ;;
  *)
    echo "node '{{ cniConfig.bridge }}:' is not of type bridge: $ngout" >&2
    exit 1
  esac
  # get host network interface name
  if ! ngout="$(ngctl msg '{{ cniConfig.bridge }}:link0' getifname)"; then
    echo "getifname failed on '{{ cniConfig.bridge }}:link0'" >&2
    exit 1
  fi
  host_if="${ngout#*[Aa][Rr][Gg][Ss]:[[:space:]]\"}"
  host_if="${host_if%%\"*}"
  host_if_config="$(ifconfig -D "$host_if")"
else
  case "$ngout" in
  *[Nn][Oo][[:space:]][Ss][Uu][Cc][Hh]*)
    # bridge node does not exist
    if host_if_config="$(ifconfig -D '{{ cniConfig.bridge }}')"; then
      # we cannot get into this state because the host interface is renamed
      # after renaming the bridge node.
      echo "interface '{{ cniConfig.bridge }}' is not connected to bridge" 2>&1
      exit 1
    fi
    # host interface does not exist. auto setup eiface + bridge
    if ! kldstat -qm ng_eiface; then kldload ng_eiface; fi
    if ! kldstat -qm ng_bridge; then kldload ng_bridge; fi
    host_if="$(create_eiface)"
    on_exit="$on_exit ngctl shutdown ${host_if}: || true; "
    trap "$on_exit" EXIT
    ngctl mkpeer "$host_if": bridge ether link0
    ngctl name "$host_if":ether '{{ cniConfig.bridge }}'
    ifconfig "$host_if" name '{{ cniConfig.bridge }}' > /dev/null
    host_if='{{ cniConfig.bridge }}'
    host_if_config="$(ifconfig -D '{{ cniConfig.bridge }}')"
    ;;
  *)
    echo "error getting bridge node at '{{ cniConfig.bridge }}:': $ngout" >&2
    exit 1
    ;;
  esac
fi

host_mac="${host_if_config##*[[:space:]]ether[[:space:]]}"
host_mac="${host_mac%%[[:space:]]*}"

ifconfig "$host_if" up

# setup jail interface
if ! jail_if="$(create_eiface)"; then
  if kldstat -qm ng_eiface; then
    echo "error creating eiface node" >&2
    exit 1
  fi
  kldload ng_eiface
  jail_if="$(create_eiface)"
fi
on_exit="$on_exit ngctl shutdown ${jail_if}: || true; "; trap "$on_exit" EXIT
link=$(("${jail_if##*[^0-9]}" + 100))

ifconfig "$jail_if" vnet '{{ env.CNI_CONTAINERID }}'
ifconfig -j '{{ env.CNI_CONTAINERID }}' "$jail_if" name '{{ env.CNI_IFNAME }}' > /dev/null

{% if cniArgs.MAC is defined %}
ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' ether '{{ cniArgs.MAC }}' > /dev/null
{% endif %}

jail_if_config="$(ifconfig -j '{{ env.CNI_CONTAINERID }}' -D '{{ env.CNI_IFNAME }}')"
jail_mac="${jail_if_config##*[[:space:]]ether[[:space:]]}"
jail_mac="${jail_mac%%[[:space:]]*}"

ifconfig -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}' up

if ! ngctl mkpeer "$jail_if": bpf ether jail; then
  if kldstat -qm ng_pbf; then
    echo "error creating bpf node on '$jail_if:ether'" >&2
    exit 1
  fi
  kldload ng_bpf
  ngctl mkpeer "$jail_if": bpf ether jail
fi
on_exit="ngctl shutdown ${jail_if}:ether || true; $on_exit"
trap "$on_exit" EXIT
ngctl name "$jail_if":ether "bpf$jail_if"

# connect jail to the bridge
ngctl connect "bpf$jail_if": '{{ cniConfig.bridge }}:' bridge link"$link"

# jail -> bridge filter
prog="$(bpf_prog "ether src $jail_mac and ether dst $host_mac")"
ngctl msg "bpf$jail_if": setprogram \
  "{ thisHook=\"jail\" ifMatch=\"bridge\" ifNotMatch=\"debug\" $prog }"

# bridge -> jail filter
prog="$(bpf_prog "ether src $host_mac and ether dst $jail_mac")"
ngctl msg "bpf$jail_if": setprogram \
  "{ thisHook=\"bridge\" ifMatch=\"jail\" ifNotMatch=\"debug\" $prog }"

trap '' EXIT
exit 0
