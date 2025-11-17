{% set volumes_dir = "/var/db/containers/storage/volumes" %}
{% for volume in oci.mounts|default([])|selectattr("type", "eq", "nullfs") %}

{% if volume.source == volumes_dir + "/pkgcache/_data" %}

set -e
volume='{{ volumes_dir }}/pkgcache/_data'
zfs snapshot zroot/jenkins/pkgcache@{{ containerId }}
zfs clone zroot/jenkins/pkgcache@{{ containerId }} zroot/jenkins/pkgcache-{{ containerId }}
dd if=/dev/random bs=512 count=1 > '/var/db/containers/pkgcache-{{ containerId }}/.challenge'
rm -f '/var/db/containers/pkgcache-{{ containerId }}/.challenge.sig'
sed -i -e "s|\"$volume\"|\"/var/db/containers/pkgcache-{{ containerId }}\"|" '{{ bundle }}/config.json'

{% elif volume.source == volumes_dir + "/ports/_data" %}

set -e
volume='{{ volumes_dir }}/ports/_data'
source='/var/db/containers/ports'
{% if "ro" in volume.options|default([]) %}
zfs snapshot zroot/jenkins/ports@{{ containerId }}
source="$source"/.zfs/snapshot/{{ containerId }}
{% endif %}
sed -i -e "s|\"$volume\"|\"$source\"|" '{{ bundle }}/config.json'

{% elif volume.source == volumes_dir + "/packages/_data" %}

set -e
volume='{{ volumes_dir }}/packages/_data'
source='/var/cache/pkgbuild/FreeBSD-14-amd64/local'
sed -i -e "s|\"$volume\"|\"$source\"|" '{{ bundle }}/config.json'

{% endif %}

{% endfor %}
