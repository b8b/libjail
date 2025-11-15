set -e

ensure_bridge()
{
  local name=""
  local driver_name=""
  local ngout
  # check bridge interface
  if driver_name="$(ifconfig -D "{{ cniConfig.bridge }}")"; then
    driver_name="${driver_name##*drivername:[[:space:]]}"
    driver_name="${driver_name%%[[:space:]]*}"
  fi
  # check bridge
  if ! ngout="$(ngctl show -n "{{ cniConfig.bridge }}:link1")"; then
    ngout="$(ngctl -f - << __EOF__
mkpeer . bridge new_bridge link0
name .:new_bridge {{ cniConfig.bridge }}
mkpeer {{ cniConfig.bridge }}: eiface link1 ether
show -n {{ cniConfig.bridge }}:link1
__EOF__
)"
  fi
  set -- $ngout
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
  if [ -z "$driver_name" ]; then
    ifconfig "$name" name "{{ cniConfig.bridge }}"
  elif [ "$driver_name" != "$name" ]; then
    echo "{{ cniConfig.bridge }}: unexpected driver name '$driver_name'"
  fi
  service netif start "{{ cniConfig.bridge }}"
}

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

ensure_bridge

jail_if="$(create_eiface)"
trap 'ngctl shutdown "$jail_if":' EXIT

echo "created $jail_if" >&2

set -x

link=$(("${jail_if##*[^0-9]}" + 100))
ngctl connect "$jail_if": "{{ cniConfig.bridge }}": ether link"$link"

ifconfig "$jail_if" vnet "{{ env.CNI_CONTAINERID }}"
ifconfig -j "{{ env.CNI_CONTAINERID }}" "$jail_if" name "{{ env.CNI_IFNAME }}" > /dev/null

trap '' EXIT
