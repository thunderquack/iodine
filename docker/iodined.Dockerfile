FROM debian:bookworm-slim AS builder

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    build-essential \
    git \
    make \
    zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
COPY Makefile /work/
COPY src /work/src
COPY man /work/man
COPY README.md LICENSE CHANGELOG /work/

RUN sed -i 's/\r$//' /work/src/osflags

RUN make TARGETOS=Linux all

FROM scratch AS export

COPY --from=builder /work/bin/iodined /out/iodined

FROM debian:bookworm-slim AS runtime

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    ca-certificates \
    libcap2-bin \
    net-tools \
    zlib1g \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /work/bin/iodined /usr/local/sbin/iodined
COPY docker/iodined-entrypoint.sh /usr/local/bin/iodined-entrypoint.sh

RUN chmod +x /usr/local/bin/iodined-entrypoint.sh

EXPOSE 53/udp

ENTRYPOINT ["/usr/local/bin/iodined-entrypoint.sh"]
