FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends samba rpcbind rpcsvc-proto libtirpc-dev build-essential autoconf automake pkg-config flex bison curl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN curl -fL https://github.com/unfs3/unfs3/archive/refs/tags/unfs3-0.11.0.tar.gz -o /tmp/unfs.tar.gz && tar xf /tmp/unfs.tar.gz -C /tmp && cd /tmp/unfs3-unfs3-0.11.0 && ./bootstrap && ./configure && make -j4 && make install && rm -rf /tmp/unfs*
RUN useradd -M -s /usr/sbin/nologin player-test && printf 'player-test-password\nplayer-test-password\n' | smbpasswd -s -a player-test
COPY smb.conf /etc/samba/smb.conf
COPY exports /etc/exports
COPY start.sh /start.sh
CMD ["sh", "/start.sh"]
