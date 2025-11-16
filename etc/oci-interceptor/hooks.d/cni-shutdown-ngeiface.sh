driver_name="$(ifconfig -D -j '{{ env.CNI_CONTAINERID }}' '{{ env.CNI_IFNAME }}')" || exit 0
driver_name="${driver_name##*drivername:[[:space:]]}"
driver_name="${driver_name%%[[:space:]]*}"

shutdown_eiface()
{
  local path="$1"
  local out
  local name=""
  local type=""
  if out="$(ngctl show -n "$path")"; then
    set -- $out
    while [ "$#" -gt 0 ]; do
      case "$1" in
      [Nn][Aa][Mm][Ee]:)
        shift
        name="$1"
        ;;
      [Tt][Yy][Pp][Ee]:)
        shift
        type="$1"
        ;;
      esac
      shift
    done
  fi
  if [ "$type" = "eiface" ]; then
    ngctl shutdown "$path"
  fi
}

shutdown_eiface "$driver_name":ether
ngctl shutdown "$driver_name":

exit 0
