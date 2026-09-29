-- =============================================================================
-- Farzandim AI — servis rollarini yaratish (production / staging)
--
-- Bir marta, har muhit uchun — DBA yoki DigitalOcean `doadmin` nomidan:
--
--   psql "$DB_ADMIN_URL" \
--     -v school_password="$SCHOOL_DB_PASSWORD" \
--     -v mobile_password="$MOBILE_DB_PASSWORD" \
--     -v admin_password="$ADMIN_DB_PASSWORD" \
--     -f scripts/db/app-roles.sql
--
-- yoki qulayroq: scripts/db-roles.sh (parollarni muhitdan oladi).
--
-- Qayta ishga tushirish xavfsiz: rol bor bo'lsa — parol va ulanish chegarasi
-- yangilanadi. Huquqlar migratsiyada (R__app_role_grants.sql); migratsiya
-- allaqachon o'tgan bo'lsa, oxirida shu yerda ham qo'llanadi.
--
-- Ulanish chegarasi — har servisning DB tomonidagi "shifti": servis
-- hovuzi noto'g'ri sozlansa ham boshqalarning ulanishini yeb qo'ymaydi.
-- Standart: school 10, mobile 10, admin 4 (+ migratsiya egasi) ≤ 22.
-- =============================================================================

\set ON_ERROR_STOP on

\if :{?school_password}
\else
  \echo 'XATO: -v school_password=... berilmagan'
  \quit 1
\endif
\if :{?mobile_password}
\else
  \echo 'XATO: -v mobile_password=... berilmagan'
  \quit 1
\endif
\if :{?admin_password}
\else
  \echo 'XATO: -v admin_password=... berilmagan'
  \quit 1
\endif

\if :{?school_conn_limit}
\else
  \set school_conn_limit 10
\endif
\if :{?mobile_conn_limit}
\else
  \set mobile_conn_limit 10
\endif
\if :{?admin_conn_limit}
\else
  \set admin_conn_limit 4
\endif

-- Rollar: yo'q bo'lsa — yaratiladi
SELECT format('CREATE ROLE farzandim_school LOGIN PASSWORD %L', :'school_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'farzandim_school') \gexec
SELECT format('CREATE ROLE farzandim_mobile LOGIN PASSWORD %L', :'mobile_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'farzandim_mobile') \gexec
SELECT format('CREATE ROLE farzandim_admin LOGIN PASSWORD %L', :'admin_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'farzandim_admin') \gexec

-- Parol va chegaralar har safar yangilanadi (parolni almashtirish ham shu yo'l)
SELECT format('ALTER ROLE farzandim_school WITH LOGIN PASSWORD %L CONNECTION LIMIT %s',
              :'school_password', :'school_conn_limit') \gexec
SELECT format('ALTER ROLE farzandim_mobile WITH LOGIN PASSWORD %L CONNECTION LIMIT %s',
              :'mobile_password', :'mobile_conn_limit') \gexec
SELECT format('ALTER ROLE farzandim_admin WITH LOGIN PASSWORD %L CONNECTION LIMIT %s',
              :'admin_password', :'admin_conn_limit') \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO farzandim_school, farzandim_mobile, farzandim_admin',
              current_database()) \gexec

-- Servis rollari `public` sxemasida jadval yarata olmasin (PG15+ da standart
-- holatda shunday, eski bazalar uchun aniq yozamiz)
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Migratsiya allaqachon o'tgan bo'lsa — huquqlarni hozir qo'llaymiz.
-- Diqqat: funksiya jadval EGASI nomidan ishlashi kerak; DBA foydalanuvchisi
-- egasi bo'lmasa, bu qator o'rniga keyingi migratsiya qadamini ishga tushiring.
SELECT 'SELECT farzandim_apply_grants()'
WHERE EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'farzandim_apply_grants') \gexec

\echo 'Tayyor: farzandim_school, farzandim_mobile, farzandim_admin'
