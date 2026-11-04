# libjail

JVM library and tools to work with FreeBSD jails

## patchwork

`patchwork/` is where this project is being made obsolete: instead of solving the
same problems in libjail, they are being fixed in the upstream tools (podman,
container-libs, ocijail) and in the CNI plugin `ngbridge`. The directory builds a
FreeBSD container runtime image from those trees.

Those trees are git submodules, so a plain clone is not enough:

```
git submodule update --init --recursive
```

`patchwork/build*.sh` needs `podman` with valid storage configuration. It builds
`patchwork/bin/{podman,ocijail,ngbridge,ngbridge-cni}` in a throwaway bootstrap
jail and then the podman image itself:

```
cd patchwork

# optionally switch to podman-5.8 branch for `CNI` support
git -C container-libs switch podman-5.8
git -C podman switch v5.8

./build-minimal-image.sh
./build-podman.sh
./build-podman-image.sh
```
