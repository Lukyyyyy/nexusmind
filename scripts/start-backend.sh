#!/usr/bin/env bash

set -euo pipefail

PROFILE="${1:-docker}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=lib/env.sh
source "$ROOT/scripts/lib/env.sh" "$ROOT"

if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Backend port $BACKEND_PORT is already in use." >&2
  lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >&2 || true
  exit 1
fi

cd "$ROOT/backend"
echo "Starting NexusMind backend on profile '$PROFILE'..."
mvn spring-boot:run \
  "-Dspring-boot.run.profiles=$PROFILE" \
  "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
