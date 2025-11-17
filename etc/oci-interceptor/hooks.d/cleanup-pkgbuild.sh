if [ -d '/var/db/containers/pkgcache-{{ containerId }}' ]; then
  if ssh-keygen -Y verify -f /root/doozer.pub -I doozer -n file \
    -s /var/db/containers/pkgcache-{{ containerId }}/.challenge.sig <\
       /var/db/containers/pkgcache-{{ containerId }}/.challenge
  then
    lockf '/var/db/containers/pkgcache.lock' \
      rsync -a '/var/db/containers/pkgcache-{{ containerId }}/.' '/var/db/containers/pkgcache'
  fi
fi
zfs destroy zroot/jenkins/pkgcache-{{ containerId }}
zfs destroy zroot/jenkins/pkgcache@{{ containerId }}
zfs destroy zroot/jenkins/ports@{{ containerId }}
