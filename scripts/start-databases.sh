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

docker compose up -d oracle-db

echo "Waiting for primary database health..."
for _ in {1..80}; do
  if docker compose ps oracle-db | grep -q healthy; then
    break
  fi
  sleep 10
done

echo "Copying generated primary password file for True Cache startup..."
docker cp semantic-cache-oracle-db:/opt/oracle/product/26ai/dbhomeFree/dbs/orapwFREE db/orapwFREE
chmod 0644 db/orapwFREE

docker compose up -d true-cache

echo "Waiting for True Cache container health..."
for _ in {1..80}; do
  if docker compose ps true-cache | grep -q healthy; then
    break
  fi
  sleep 10
done

echo "Aligning True Cache password file with the primary password file..."
docker exec --user root semantic-cache-true-cache bash -lc 'cp /var/tmp/orapwFREE /opt/oracle/product/26ai/dbhomeFree/dbs/orapwFREE && chown oracle:oinstall /opt/oracle/product/26ai/dbhomeFree/dbs/orapwFREE && chmod 640 /opt/oracle/product/26ai/dbhomeFree/dbs/orapwFREE'

echo "Configuring True Cache services from the primary database..."
docker exec semantic-cache-oracle-db bash -lc "/opt/oracle/product/26ai/dbhomeFree/bin/dbca -configureDatabase -configureTrueCacheInstanceService -sourceDB FREE -trueCacheConnectString 172.28.0.11:1521/FREE_TC -trueCacheServiceName semcache_tc -serviceName FREE -sysPassword '${ORACLE_PWD}' -silent"
docker exec semantic-cache-oracle-db bash -lc "/opt/oracle/product/26ai/dbhomeFree/bin/dbca -configureDatabase -configureTrueCacheInstanceService -sourceDB FREE -trueCacheConnectString 172.28.0.11:1521/freepdb1 -trueCacheServiceName semcache_pdb_tc -serviceName FREEPDB1 -pdbName FREEPDB1 -sysPassword '${ORACLE_PWD}' -silent"

echo "Oracle AI Database 26ai Free and Oracle True Cache services are configured."
