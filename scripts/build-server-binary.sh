#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/dist/server-binary}"

mkdir -p "$OUT_DIR"

docker buildx build \
  -f "$ROOT_DIR/docker/iodined.Dockerfile" \
  --target export \
  --output "type=local,dest=$OUT_DIR" \
  "$ROOT_DIR"

echo "Server build artifacts exported to: $OUT_DIR"
