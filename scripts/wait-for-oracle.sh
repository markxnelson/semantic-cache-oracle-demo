#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  cp .env.example .env
fi

set -a
source .env
set +a

echo "Waiting for primary Oracle AI Database 26ai Free service..."
for _ in {1..80}; do
  if docker exec semantic-cache-oracle-db bash -lc "echo 'select 1 from dual;' | sqlplus -s ${APP_USER}/${APP_PASSWORD}@//localhost:1521/FREEPDB1" | grep -q 1; then
    echo "primary database: ready"
    break
  fi
  sleep 10
done

echo "Waiting for Oracle True Cache lookup service..."
for _ in {1..80}; do
  if docker exec semantic-cache-true-cache bash -lc "echo 'select 1 from dual;' | sqlplus -s ${APP_USER}/${APP_PASSWORD}@//localhost:1521/semcache_pdb_tc" | grep -q 1; then
    echo "true cache: ready"
    echo "app schema: ${APP_USER}"
    exit 0
  fi
  sleep 10
done

echo "Timed out waiting for Oracle True Cache." >&2
exit 1
