-- =============================================================================
-- Farzandim AI — dev seed #3: servis rollari (faqat `%dev`)
--
-- Production'da rollar scripts/db/app-roles.sql bilan, haqiqiy parollar
-- bilan bir marta yaratiladi. Bu yerdagi parollar faqat lokal muhit uchun
-- va application.properties'dagi `%dev` qiymatlari bilan bir xil.
--
-- Huquqlarning o'zi — R__app_role_grants.sql (versiyali migratsiyalardan
-- keyin avtomatik ishlaydi).
-- =============================================================================

DO $$
DECLARE
    spec record;
BEGIN
    FOR spec IN
        SELECT * FROM (VALUES
            ('farzandim_school', 'farzandim_school_dev', 12),
            ('farzandim_mobile', 'farzandim_mobile_dev', 12),
            ('farzandim_admin',  'farzandim_admin_dev',   6)
        ) AS t(role_name, dev_password, conn_limit)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = spec.role_name) THEN
            EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L CONNECTION LIMIT %s',
                           spec.role_name, spec.dev_password, spec.conn_limit);
        END IF;
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), spec.role_name);
    END LOOP;
END $$;
