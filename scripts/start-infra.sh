#!/usr/bin/env bash

set -euo pipefail

SKIP_INIT=false
SKIP_MINERU=true
REQUIRE_MINERU=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-init)
      SKIP_INIT=true
      ;;
    --with-mineru)
      SKIP_MINERU=false
      ;;
    --require-mineru)
      SKIP_MINERU=false
      REQUIRE_MINERU=true
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=lib/env.sh
source "$ROOT/scripts/lib/env.sh" "$ROOT"

echo "Starting NexusMind core infrastructure..."
docker compose -f "$ROOT/backend/docs/docker-compose.yaml" up -d

if [[ "$SKIP_INIT" == true ]]; then
  exit 0
fi

echo "Waiting for MySQL..."
mysql_ready=false
for _ in {1..30}; do
  if docker exec -e "MYSQL_PWD=$MYSQL_ROOT_PASSWORD" mysql mysqladmin ping -uroot --silent >/dev/null 2>&1; then
    mysql_ready=true
    break
  fi
  sleep 2
done

if [[ "$mysql_ready" != true ]]; then
  echo "MySQL container did not become ready in time." >&2
  exit 1
fi

docker exec -e "MYSQL_PWD=$MYSQL_ROOT_PASSWORD" mysql mysql -uroot \
  -e "CREATE DATABASE IF NOT EXISTS nexusmind DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" >/dev/null
echo "Database nexusmind is ready."

echo "Preparing MinIO bucket uploads..."
if ! docker exec minio sh -c "mc alias set local http://127.0.0.1:19000 '$MINIO_ACCESS_KEY' '$MINIO_SECRET_KEY' >/dev/null 2>&1 && mc mb -p local/uploads >/dev/null 2>&1 || true" >/dev/null 2>&1; then
  echo "Warning: MinIO bucket initialization did not complete. You may need to create bucket 'uploads' manually." >&2
fi

if [[ "$SKIP_MINERU" != true ]]; then
  echo "Starting MinerU service..."
  if ! docker compose -f "$ROOT/backend/docs/docker-compose.yaml" --profile mineru up -d mineru-api; then
    message="MinerU service did not start. Tika parsing and the rest of NexusMind can still run; choose MinerU only after mineru-api is ready."
    if [[ "$REQUIRE_MINERU" == true ]]; then
      echo "$message" >&2
      exit 1
    fi
    echo "Warning: $message" >&2
  fi
fi

echo "Infrastructure startup requested. Check with: docker ps"
