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

exec "$@"
