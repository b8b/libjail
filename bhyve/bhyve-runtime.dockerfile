ARG BASE_IMAGE=freebsd-minimal

# build espadm
FROM ${BASE_IMAGE} as builder

RUN pkg install -y \
    FreeBSD-set-devel rust

ADD espadm /usr/src/espadm

WORKDIR /usr/src/espadm

RUN cargo build --release

# build runtime image
FROM ${BASE_IMAGE}

RUN pkg install -y \
    FreeBSD-bhyve \
    FreeBSD-acpi \
    FreeBSD-pf \
    FreeBSD-ssh \
    FreeBSD-zstd \
    bhyve-firmware \
    edk2-bhyve \
    grub2-bhyve && \
    pkg info -la > /.installed && \
    pkg clean -y && \
    pkg delete -fy pkg && \
    rm -Rf /var/cache/pkg /var/db/pkg/repos

# install espadm
COPY --from=builder /usr/src/espadm/target/release/espadm /bin/

ADD run.sh /run.sh

RUN chmod +x /run.sh

CMD /run.sh
