# bhyve

Bhyve VMs as container images. A VM is started the way a container is.

```shell
podman run --rm -it --annotation org.freebsd.jail.allow.vmm=true bhyve-freebsd
```

> **Warning — the guest rolls back on every start.** `run.sh` rebuilds
> `disk1.img` from `/disk1` and reformats the ESP from `/disk0` before it execs
> `bhyve`, so nothing the guest writes survives the container. A guest reboot is
> not handled either: `bhyve` exits when the guest reboots, which ends the
> container, and the next start boots the pristine image again.
>
> Rebooting would need the guest to bring up its network on its own —
> `cloud-init`, or `nuageinit` for FreeBSD guests — instead of the `rc.conf`
> that `run.sh` writes into `/disk1` at every start.

## Images

The Dockerfiles here are built with `bhyve/` as context.

- `bhyve-runtime.dockerfile` — the runtime image: `bhyve` with `bhyve-firmware`,
  `edk2-bhyve`, `grub2-bhyve`, plus `espadm` (built from `espadm/` in a rust
  builder stage) and `run.sh` as `CMD`. It leaves `/.installed` (the file list
  of its installed packages) behind for the VM images to read.
- `freebsd-vm.dockerfile` — a bootable FreeBSD on top of that runtime. The
  `vmroot` stage installs bootloader, `GENERIC` kernel and selected modules into
  `/boot/efi`, and the guest root in `/`. 
  A builder stage then unpacks it into `/disk1`, moves `/boot/efi` to `/disk0`, 
  and records every file that has been installed by a package in `bhyve-runtime`
  already into `/disk1.deleted.mtree` (and removes it).
- `generic-vm.dockerfile` — picks up whatever the base image (`BASE_IMAGE`,
  default `generic-vm`) provides: `/boot/efi` becomes `/disk0` and is removed
  from the root filesystem, everything else becomes `/disk1`. It assumes a
  complete guest root already, so it neither selects modules nor sets policy
  nor computes a sharing manifest. A stub: the split works, `run.sh` is work in
  progress and boots FreeBSD guests only.
- `freebsd14-vm.dockerfile` — the `freebsd-vm` guest root for a
  `14.5-RELEASE-p3` base; same as `freebsd-vm.dockerfile` but no shared files.

Kernel modules and `mac_do` policy are handled by the freebsd vm images:
the `MODULES` build arg (default `zfs opensolaris mac_do pf netgraph ng_socket
ng_bridge ng_eiface ng_bpf`) is the list of modules that are gzipped into the
ESP and preloaded in `loader.conf` — `zfs` and `opensolaris` are needed to mount
the ZFS root — and `security.mac.do.rules` lets users in the `wheel` group
switch to root inside the guest.

`freebsd-vm` must be built from the same `BASE_IMAGE` as `bhyve-runtime`, since
the manifest entries are resolved against the runtime's own filesystem at boot.

## Expected layout

None of these images contains disks; disks are made from a tree at start:

- `/disk0` — contents of the EFI system partition
- `/disk1` — guest root filesystem
- `/disk1.deleted.mtree` — optional (`freebsd-vm` only), mtree manifest of the
  files the runtime provides

`espadm/README.md` describes the ESP tool; `espadm --list` prints the tree.

## Build

```sh
cd bhyve
podman build -f bhyve-runtime.dockerfile -t bhyve-runtime .
podman build -f freebsd-vm.dockerfile -t freebsd-vm .
podman build -f generic-vm.dockerfile \
    --build-arg BASE_IMAGE=<guest-root-image> -t <name> .
```

A reduced module set for the guest, e.g. without the netgraph modules:

```sh
podman build -f freebsd-vm.dockerfile \
    --build-arg MODULES='zfs opensolaris mac_do pf' -t freebsd-vm .
```

The default `BASE_IMAGE` is `freebsd-minimal`, the base image that
`patchwork/build-minimal-image.sh` builds. Whatever is used has to provide the
base system tools the build and `run.sh` need (`mkimg`, `makefs`, `tar`).

## Boot

`run.sh`, in order:

1. egress interface and gateway from `EGRESS_IFACE` and `EGRESS_GATEWAY`, else
   from the default route, else `eth0`
2. guest network from the egress interface: the guest takes its MAC address and
   its CIDR, or DHCP when the interface has no address. With a `/32` the gateway
   is added as a host route on `GUEST_IFACE`.
3. `disk0.img`: `mkimg -s gpt` with an ESP at 1 MiB (`ESP_SIZE_MIB`), unless the
   file exists; `espadm --source /disk0` formats it FAT32 and copies the tree,
   after `boot/loader.conf.d/rootdev.conf` was added to boot from
   `zfs:zroot/ROOT/default`
4. `/disk1/etc/rc.conf`: hostname, address or DHCP, default router,
   `zfs_enable`; `rc.conf.d/vmroutes.conf` for the `/32` case; `authorized_keys`
   and `sshd_enable` when `AUTHORIZED_KEYS` is set
5. `disk1.img`: `makefs -t zfs` with pool `zroot`, bootfs
   `zroot/ROOT/default`, size `ROOT_SIZE`, from `/disk1`, or from the
   `.deleted.mtree` manifest merged with `/disk1` (`-D`, because paths appear in
   both)
6. `exec bhyve`: UEFI bootrom, the two virtio-blk disks, virtio-rnd, LPC, and a
   virtio-net device on the netgraph backend — `path` is the netgraph node of
   the egress interface, `peerhook=lower`, and the guest MAC is the egress
   interface's MAC

The serial console is only attached when stdin is a tty, so the VM has a console
with `podman run -it` and none in the background. `SIGTERM` to the container
reaches `bhyve`, which then triggers an ACPI poweroff in the guest.

## Environment

- `MEM_SIZE` — guest memory, default `1g`
- `NCPU` — guest vCPUs, default `2`
- `ESP_SIZE_MIB` — ESP size, default `64` (keep it above 32 for FAT32)
- `ROOT_SIZE` — ZFS pool size, default `100g`
- `VM_NAME` — bhyve VM name, default the container's hostname
- `EGRESS_IFACE`, `EGRESS_GATEWAY` — see above
- `GUEST_IFACE` — guest interface for the `/32` host route, default `vtnet0`
- `AUTHORIZED_KEYS` — enables `sshd` in the guest and installs the key

## Requirements on the host

- `vmm.ko` loaded, and `/dev/vmm`, `/dev/vmm.io` and `/dev/vmmctl` visible in
  the container, with `allow.vmm` on the jail and on the container — see
  `patchwork/ocijail/examples/nested-podman/README.md`
- `netgraph` and the node of the egress interface, i.e. the egress interface is
  an `ng_eiface` as `ngbridge` creates them
- a writable working directory for `disk0.img` and `disk1.img`
