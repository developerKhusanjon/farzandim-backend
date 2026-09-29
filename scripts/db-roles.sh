#!/usr/bin/env bash
# =============================================================================
# Servis rollarini yaratish/yangilash — scripts/db/app-roles.sql uchun o'ram.
#
#   DB_ADMIN_URL="postgresql://doadmin:...@host:25060/farzandim?sslmode=require" \
#   SCHOOL_DB_PASSWORD=... MOBILE_DB_PASSWORD=... ADMIN_DB_PASSWORD=... \
#   scripts/db-roles.sh
#
# Lokal psql bo'lmasa — docker-compose'dagi postgres konteyneri ichidagi
# psql ishlatiladi (faqat lokal baza uchun).
# Dev muhitda bu skript kerak emas: rollar V902 dev seed'ida yaratiladi.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

: "${SCHOOL_DB_PASSWORD:?SCHOOL_DB_PASSWORD berilmagan}"
: "${MOBILE_DB_PASSWORD:?MOBILE_DB_PASSWORD berilmagan}"
: "${ADMIN_DB_PASSWORD:?ADMIN_DB_PASSWORD berilmagan}"

args=(
  -v "school_password=${SCHOOL_DB_PASSWORD}"
  -v "mobile_password=${MOBILE_DB_PASSWORD}"
  -v "admin_password=${ADMIN_DB_PASSWORD}"
)
[[ -n "${SCHOOL_CONN_LIMIT:-}" ]] && args+=(-v "school_conn_limit=${SCHOOL_CONN_LIMIT}")
[[ -n "${MOBILE_CONN_LIMIT:-}" ]] && args+=(-v "mobile_conn_limit=${MOBILE_CONN_LIMIT}")
[[ -n "${ADMIN_CONN_LIMIT:-}" ]] && args+=(-v "admin_conn_limit=${ADMIN_CONN_LIMIT}")

if command -v psql >/dev/null 2>&1; then
  : "${DB_ADMIN_URL:?DB_ADMIN_URL berilmagan (postgresql://user:parol@host:port/baza)}"
  psql "$DB_ADMIN_URL" "${args[@]}" -f scripts/db/app-roles.sql
else
  db="${DB_NAME:-farzandim}"
  echo "psql topilmadi — farzandim-postgres konteyneri orqali ($db bazasi)"
  docker exec -i farzandim-postgres psql -U "${POSTGRES_USER:-farzandim}" -d "$db" "${args[@]}" -f - \
    < scripts/db/app-roles.sql
fi
