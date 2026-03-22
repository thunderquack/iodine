#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="${1:-iodine-server:latest}"

docker build \
  -f "$ROOT_DIR/docker/iodined.Dockerfile" \
  -t "$IMAGE_TAG" \
  "$ROOT_DIR"

echo "Built image: $IMAGE_TAG"
