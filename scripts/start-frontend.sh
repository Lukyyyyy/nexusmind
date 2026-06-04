#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=lib/env.sh
source "$ROOT/scripts/lib/env.sh" "$ROOT"

cd "$ROOT/frontend"

if [[ ! -d node_modules ]]; then
  echo "Installing frontend dependencies..."
  pnpm install --frozen-lockfile
fi

echo "Starting NexusMind frontend..."
pnpm dev
