#!/usr/bin/env bash
# =============================================================================
# Ma'lumotlar bazasi migratsiyasi — ALOHIDA deploy qadami.
#
# Tartib (har relizda):
#   1. scripts/migrate.sh        ← shu skript: migratsiya va chiqish
#   2. school-backend, mobile-backend, admin-backend (istalgan tartibda)
#
# Servislar ishga tushishda faqat TEKSHIRADI (quarkus.flyway.validate-at-start):
# 1-qadam o'tkazib yuborilsa, ular "pending migration" deb ishga tushmaydi —
# yarim yangilangan sxemada ishlab, runtime'da SQL xatosi bermaydi.
#
# Migratsiya sxema EGASI nomidan ishlaydi (DDL huquqi faqat unda); servislar
# esa o'z cheklangan rollari bilan (farzandim_school / _mobile / _admin).
#
#   DB_URL="jdbc:postgresql://host:25060/farzandim?sslmode=require&stringtype=unspecified" \
#   DB_MIGRATION_USER=farzandim_owner DB_MIGRATION_PASSWORD=... \
#   scripts/migrate.sh
#
# Muhim: migratsiya PgBouncer orqali EMAS, bazaga to'g'ridan-to'g'ri ulanadi
# (DigitalOcean'da 25060-port, pool porti 25061 emas) — Flyway sessiya
# darajasidagi qulf (advisory lock) ishlatadi.
#
# Native konteyner bilan (App Platform PRE_DEPLOY job, CI):
#   docker run --rm -e QUARKUS_INIT_AND_EXIT=true -e FLYWAY_MIGRATE=true \
#     -e FLYWAY_VALIDATE=false -e DB_URL -e DB_USER="$DB_MIGRATION_USER" \
#     -e DB_PASSWORD="$DB_MIGRATION_PASSWORD" -e AI_WORKER=false -e MAINTENANCE=false \
#     farzandim/school-backend:<versiya>
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

: "${DB_URL:?DB_URL berilmagan}"
: "${DB_MIGRATION_USER:?DB_MIGRATION_USER berilmagan (sxema egasi)}"
: "${DB_MIGRATION_PASSWORD:?DB_MIGRATION_PASSWORD berilmagan}"

JAR="${JAR:-school-backend/target/quarkus-app/quarkus-run.jar}"
if [[ ! -f "$JAR" ]]; then
  echo "Artefakt topilmadi: $JAR"
  echo "Avval: ./mvnw -pl school-backend -am package -DskipTests"
  exit 1
fi

# init-and-exit: Flyway migratsiyasi bajariladi va jarayon darhol chiqadi.
# Bu nusxa HTTP so'rov qabul qilmaydi, AI worker va fon ishlari ishlamaydi.
DB_USER="$DB_MIGRATION_USER" \
DB_PASSWORD="$DB_MIGRATION_PASSWORD" \
FLYWAY_MIGRATE=true \
FLYWAY_VALIDATE=false \
QUARKUS_INIT_AND_EXIT=true \
AI_WORKER=false \
MAINTENANCE=false \
java ${JAVA_OPTS:-} -jar "$JAR"

echo "Migratsiya tugadi."
