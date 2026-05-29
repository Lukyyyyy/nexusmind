#!/usr/bin/env bash
set -euo pipefail

export MINERU_MODEL_SOURCE="${MINERU_MODEL_SOURCE:-local}"

if [ -n "${MINERU_HTTP_PROXY:-}" ]; then
  export HTTP_PROXY="$MINERU_HTTP_PROXY"
  export http_proxy="$MINERU_HTTP_PROXY"
fi

if [ -n "${MINERU_HTTPS_PROXY:-}" ]; then
  export HTTPS_PROXY="$MINERU_HTTPS_PROXY"
  export https_proxy="$MINERU_HTTPS_PROXY"
fi

if [ -n "${MINERU_NO_PROXY:-}" ]; then
  export NO_PROXY="$MINERU_NO_PROXY"
  export no_proxy="$MINERU_NO_PROXY"
fi

PERSIST_DIR="${MINERU_PERSIST_DIR:-/root/.cache/mineru}"
CONFIG_FILE="${MINERU_CONFIG_FILE:-/root/mineru.json}"
PERSISTED_CONFIG="$PERSIST_DIR/mineru.json"

mkdir -p "$PERSIST_DIR"

if [ -f "$PERSISTED_CONFIG" ] && [ ! -f "$CONFIG_FILE" ]; then
  cp "$PERSISTED_CONFIG" "$CONFIG_FILE"
fi

if [ "${MINERU_SKIP_MODEL_DOWNLOAD:-false}" != "true" ]; then
  if [ ! -f "$CONFIG_FILE" ]; then
    echo "MinerU model config not found. Downloading models..."
    mineru-models-download \
      -s "${MINERU_MODEL_DOWNLOAD_SOURCE:-huggingface}" \
      -m "${MINERU_MODEL_DOWNLOAD_TYPE:-all}"
    if [ -f "$CONFIG_FILE" ]; then
      cp "$CONFIG_FILE" "$PERSISTED_CONFIG"
    fi
  else
    echo "MinerU model config already exists. Skipping model download."
  fi
else
  echo "MINERU_SKIP_MODEL_DOWNLOAD=true, skipping model download."
fi

exec "$@"
