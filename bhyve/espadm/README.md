# espadm

Manage the FAT32 EFI system partition inside a GPT disk image, in place: the
primary GPT header, the partition array and the backup header are never
touched, so it can run directly against an image `mkimg` produced. No
`efi.img`, no `dd`, no `mdconfig`/`mount`, no mtools.

## Usage

Exactly one action is required:

```
espadm --image disk0.img --source /boot/efi     # format FAT32 + populate
espadm --image disk0.img --add /some/tree       # merge, replacing collisions
espadm --image disk0.img --list                 # print the contents
```

Options:

- `--image PATH` (required) — GPT disk image
- `--partition N` — partition index (default `1`)
- `--label NAME` — FAT volume label for `--source`, upper-cased and space
  padded to 11 bytes (default `EFISYS`)

`--source` formats the partition and then copies the directory tree into the
fresh root. `--add` keeps the existing filesystem and merges the tree into it:
missing directories are created, existing files are overwritten in place,
and a path that changes type (file ↔ directory) is removed first.
`--list` opens the image read-only and prints one line per entry:

```
d            0 /EFI
d            0 /EFI/BOOT
f           25 /EFI/BOOT/BOOTX64.EFI
```

## Build

```
cargo build --release
```

The binary is self-contained; copy it into the bhyve image like the other
host-side tools (`bin/podman`, `bin/ocijail`, ...).

## Implementation

- `gptman` reads the partition table and yields the partition's byte offset and
  size; a warning is printed when the partition type is not
  `C12A7328-F81F-11D2-BA4B-00A0C93EC93B`.
- A `Read + Write + Seek` window over the image, clamped to that range, is
  handed to `fatfs`, which formats FAT32 or walks/writes the existing
  filesystem (long file names included).

Dependencies: `fatfs` 0.3, `gptman` 3, `clap` 4.

Note on the FAT type: `fatfs` needs enough clusters to honour FAT32 (at the
512-byte cluster size it picks, roughly > 32 MiB). Smaller partitions come out
as FAT16, which UEFI also accepts; the 64 MiB ESP used by the bhyve image is
FAT32.
