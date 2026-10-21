FROM alpine:3.20
RUN apk add --no-cache wireguard-tools iptables
COPY entrypoint.sh /entrypoint.sh
RUN chmod 0755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
