ARG BASE_IMAGE=generic-vm
FROM ${BASE_IMAGE} as vmroot

RUN tar -caf /vmroot.tar.zst \
        --one-file-system \
        --exclude ./vmroot.tar.zst \
        --exclude '.save-*' \
        .

FROM bhyve-runtime as builder

RUN mkdir -p /disk0 /disk1

COPY --from=vmroot /vmroot.tar.zst /vmroot.tar.zst

RUN tar -C /disk1 -xf /vmroot.tar.zst

RUN mv /disk1/boot/efi/* /disk0/

FROM bhyve-runtime

RUN mkdir -p /disk0 /disk1

COPY --from=builder /disk0 /disk0
COPY --from=builder /disk1 /disk1
