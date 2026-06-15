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

./mvnw -q -pl app -am package -DskipTests
java -jar app/target/semantic-cache-oracle-app-0.1.0-SNAPSHOT.jar

echo
echo "Validation artifacts:"
ls -1 reports/generated/validation-events.csv reports/generated/validation-summary.json reports/generated/validation-summary.md
