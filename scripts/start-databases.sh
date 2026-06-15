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

true_cache_service_exists() {
  local service_name="$1"
  docker exec semantic-cache-oracle-db bash -lc "printf '%s\n' \
    'set heading off feedback off pagesize 0 verify off' \
    \"select name from v\\\$services where upper(name) = upper('${service_name}') union select name from cdb_services where upper(name) = upper('${service_name}');\" \
    'exit' | sqlplus -s system/'${ORACLE_PWD}'@//localhost:1521/FREE | grep -qi '${service_name}'" \
    || docker exec semantic-cache-oracle-db bash -lc "/opt/oracle/product/26ai/dbhomeFree/bin/lsnrctl services | grep -q 'Service \"${service_name}\"'"
}

configure_true_cache_service() {
  local service_name="$1"
  local true_cache_connect_string="$2"
  local source_service_name="$3"
  local pdb_name="${4:-}"

  if true_cache_service_exists "$service_name"; then
    echo "True Cache service ${service_name} already exists; skipping registration."
    return
  fi

  local pdb_arg=""
  if [[ -n "$pdb_name" ]]; then
    pdb_arg="-pdbName ${pdb_name}"
  fi

  local output
  set +e
  output=$(docker exec semantic-cache-oracle-db bash -lc "/opt/oracle/product/26ai/dbhomeFree/bin/dbca -configureDatabase -configureTrueCacheInstanceService -sourceDB FREE -trueCacheConnectString ${true_cache_connect_string} -trueCacheServiceName ${service_name} -serviceName ${source_service_name} ${pdb_arg} -sysPassword '${ORACLE_PWD}' -silent" 2>&1)
  local status=$?
  set -e

  if [[ "$status" -ne 0 ]]; then
    if grep -q "DBT-19958" <<< "$output" && grep -q "${service_name}" <<< "$output"; then
      echo "True Cache service ${service_name} already exists according to DBCA; continuing."
      return
    fi
    printf '%s\n' "$output"
    return "$status"
  fi

  printf '%s\n' "$output"
}

echo "Configuring True Cache services from the primary database..."
configure_true_cache_service "semcache_tc" "172.28.0.11:1521/FREE_TC" "FREE"
configure_true_cache_service "semcache_pdb_tc" "172.28.0.11:1521/freepdb1" "FREEPDB1" "FREEPDB1"

echo "Oracle AI Database 26ai Free and Oracle True Cache services are configured."
