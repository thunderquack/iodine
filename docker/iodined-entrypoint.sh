#!/usr/bin/env sh
set -eu

if [ "${1:-}" = "iodined" ]; then
  shift
fi

if [ "$#" -gt 0 ]; then
  exec /usr/local/sbin/iodined "$@"
fi

TUNNEL_IP="${IODINED_TUNNEL_IP:-}"
TOPDOMAIN="${IODINED_TOPDOMAIN:-}"
PASSWORD="${IODINED_PASSWORD:-}"
LISTEN_IPV4="${IODINED_LISTEN_IPV4:-0.0.0.0}"
EXTERNAL_NS_IP="${IODINED_EXTERNAL_NS_IP:-}"
EXTRA_ARGS="${IODINED_EXTRA_ARGS:-}"
ENABLE_NAT="${IODINED_ENABLE_NAT:-0}"
UPLINK_IFACE="${IODINED_UPLINK_IFACE:-}"
TUNNEL_CIDR="${IODINED_TUNNEL_CIDR:-}"
TUN_DEVICE="${IODINED_TUN_DEVICE:-dns0}"

if [ -z "$TUNNEL_IP" ] || [ -z "$TOPDOMAIN" ]; then
  echo "IODINED_TUNNEL_IP and IODINED_TOPDOMAIN are required." >&2
  exit 1
fi

set -- /usr/local/sbin/iodined -f
set -- "$@" -l "$LISTEN_IPV4"

if [ -n "$PASSWORD" ]; then
  set -- "$@" -P "$PASSWORD"
fi

if [ -n "$EXTERNAL_NS_IP" ]; then
  set -- "$@" -n "$EXTERNAL_NS_IP"
fi

if [ -n "$EXTRA_ARGS" ]; then
  # shellcheck disable=SC2086
  set -- "$@" $EXTRA_ARGS
fi

set -- "$@" "$TUNNEL_IP" "$TOPDOMAIN"

if [ "$ENABLE_NAT" != "1" ]; then
  exec "$@"
fi

if [ -z "$UPLINK_IFACE" ] || [ -z "$TUNNEL_CIDR" ]; then
  echo "IODINED_UPLINK_IFACE and IODINED_TUNNEL_CIDR are required when IODINED_ENABLE_NAT=1." >&2
  exit 1
fi

"$@" &
iodined_pid=$!

i=0
while [ "$i" -lt 50 ]; do
  if ip link show "$TUN_DEVICE" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$iodined_pid" >/dev/null 2>&1; then
    wait "$iodined_pid"
    exit $?
  fi
  sleep 0.2
  i=$((i + 1))
done

if ! ip link show "$TUN_DEVICE" >/dev/null 2>&1; then
  echo "Tunnel device $TUN_DEVICE did not appear; continuing without NAT rules." >&2
  wait "$iodined_pid"
  exit $?
fi

iptables -t nat -C POSTROUTING -s "$TUNNEL_CIDR" -o "$UPLINK_IFACE" -j MASQUERADE >/dev/null 2>&1 \
  || iptables -t nat -A POSTROUTING -s "$TUNNEL_CIDR" -o "$UPLINK_IFACE" -j MASQUERADE
iptables -C FORWARD -i "$TUN_DEVICE" -o "$UPLINK_IFACE" -j ACCEPT >/dev/null 2>&1 \
  || iptables -A FORWARD -i "$TUN_DEVICE" -o "$UPLINK_IFACE" -j ACCEPT
iptables -C FORWARD -i "$UPLINK_IFACE" -o "$TUN_DEVICE" -m state --state RELATED,ESTABLISHED -j ACCEPT >/dev/null 2>&1 \
  || iptables -A FORWARD -i "$UPLINK_IFACE" -o "$TUN_DEVICE" -m state --state RELATED,ESTABLISHED -j ACCEPT

wait "$iodined_pid"
