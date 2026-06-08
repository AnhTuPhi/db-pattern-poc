#!/usr/bin/env bash
# Bring up the DBs, wait for them to be healthy.
set -euo pipefail

cd "$(dirname "$0")/.."

PROFILE="${1:-postgres}"  # postgres | core | oracle

echo "==> docker compose --profile ${PROFILE} up -d"
docker compose --profile "${PROFILE}" up -d

echo "==> Waiting for Postgres health..."
for i in {1..60}; do
  if docker inspect --format='{{.State.Health.Status}}' db-poc-postgres 2>/dev/null | grep -q healthy; then
    echo "    Postgres healthy"
    break
  fi
  sleep 2
done

if [[ "${PROFILE}" == "core" || "${PROFILE}" == "oracle" ]]; then
  echo "==> Waiting for Oracle health (first start can take ~3 min)..."
  for i in {1..120}; do
    if docker inspect --format='{{.State.Health.Status}}' db-poc-oracle 2>/dev/null | grep -q healthy; then
      echo "    Oracle healthy"
      break
    fi
    sleep 5
  done
fi

echo "==> Ready"
echo "    Postgres: jdbc:postgresql://localhost:5432/appdb  (appuser / AppUser123)"
[[ "${PROFILE}" == "core" || "${PROFILE}" == "oracle" ]] && \
  echo "    Oracle:   jdbc:oracle:thin:@localhost:1521/FREEPDB1 (appuser / AppUser123)"
