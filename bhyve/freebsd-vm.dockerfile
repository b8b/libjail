ARG BASE_IMAGE=freebsd-minimal
FROM ${BASE_IMAGE} as vmroot

ARG MODULES="zfs opensolaris mac_do pf netgraph ng_socket ng_bridge ng_eiface ng_bpf"

RUN pkg install -y \
        FreeBSD-bootloader \
        FreeBSD-kernel-generic \
        FreeBSD-acpi \
        FreeBSD-pf \
        FreeBSD-zstd \
        FreeBSD-zfs \
        FreeBSD-pam \
        FreeBSD-ssh

RUN mkdir -p /boot/efi/EFI/BOOT /boot/efi/boot/kernel

# add loader config
RUN printf 'autoboot_delay="-1"\nbeastie_disable="YES"\n' > /boot/efi/boot/loader.conf

# add boot loader
RUN loader=/boot/loader.efi; \
    [ -f "$loader" ] || loader=/boot/loader_lua.efi; \
    ln "$loader" /boot/efi/EFI/BOOT/BOOTX64.EFI && \
    cp -r /boot/defaults /boot/loader.conf.d /boot/fonts /boot/lua /boot/efi/boot/

# add kernel
RUN gzip < /boot/kernel/kernel > /boot/efi/boot/kernel/kernel.gz

# add modules
RUN for mod in ${MODULES}; do \
        gzip < /boot/kernel/"$mod".ko > /boot/efi/boot/kernel/"$mod".ko.gz; \
        echo "$mod"_load='"YES"' >> /boot/efi/boot/loader.conf; \
    done

# setup system settings
RUN printf '/dev/gpt/efiboot0\t/boot/efi\tmsdosfs\trw\t2\t2' > /etc/fstab && \
    echo 'security.mac.do.rules="gid=0>uid=0,gid=0,+gid=*"' >> /etc/sysctl.conf && \
    touch /etc/rc.conf

# setup user
RUN pw useradd -n freebsd -m -u 1001 -g staff -G wheel \
        -d /home/freebsd -s /bin/sh -w no && \
    mkdir -p /home/freebsd/.ssh && \
    touch /home/freebsd/.ssh/authorized_keys && \
    chown -R freebsd:wheel /home/freebsd/.ssh

# cleanup
RUN pkg delete -y \
        FreeBSD-bootloader \
        FreeBSD-kernel-generic && \
    pkg clean -y && \
    pkg delete -fy pkg && \
    rm -Rf /var/cache/pkg /var/db/pkg/repos

# package
RUN tar -caf /vmroot.tar.zst \
        --one-file-system \
        --exclude ./vmroot.tar.zst \
        --exclude '.save-*' \
        .

FROM bhyve-runtime as builder

RUN mkdir -p /disk0 /disk1

COPY --from=vmroot /vmroot.tar.zst /vmroot.tar.zst

RUN grep -o '/.*' /.installed > /.installed_paths

RUN tar -C /disk1 -xf /vmroot.tar.zst

RUN mv /disk1/boot/efi/* /disk0/

RUN while read -r path; do \
        if ! [ -e "/disk1$path" ]; then continue; fi; \
        if [ -L "/disk1$path" ] || ! [ -d "/disk1$path" ]; then \
            echo ".$path "; \
        fi; \
    done < /.installed_paths > /.deleted_paths

RUN xargs -n 100 tar -C /disk1 -cf - --format mtree -- < /.deleted_paths | \
    awk '!/^#/{ gsub(/ nlink=[0-9]+/, ""); print; }' > /disk1/.deleted.mtree

RUN cd /disk1 && xargs -n 100 chflags noschg .deleted.mtree < /.deleted_paths

RUN cd /disk1 && xargs -n 100 rm -- < /.deleted_paths

RUN mv /disk1/.deleted.mtree /disk1.deleted.mtree

FROM bhyve-runtime

RUN mkdir -p /disk0 /disk1

COPY --from=builder /disk0 /disk0
COPY --from=builder /disk1 /disk1
COPY --from=builder /disk1.deleted.mtree /disk1.deleted.mtree
