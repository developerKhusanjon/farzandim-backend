-- =============================================================================
-- Farzandim AI — servis rollari va ularning huquqlari (repeatable migratsiya)
--
-- Uchala servis bitta bazada, lekin har biri O'Z roli bilan ulanadi:
--
--   farzandim_school  CRM: o'quv ma'lumotiga to'liq yozadi; to'lov va ichki
--                     platforma qaydlarini ko'rmaydi
--   farzandim_mobile  ota-ona ilovasi: o'quv ma'lumotini faqat O'QIYDI;
--                     ota-ona amallariga (vazifa tasdig'i, bildirishnoma,
--                     AI chat, qurilma) yozadi; to'lov/audit/qaydlar — yo'q
--   farzandim_admin   platforma paneli: o'quv ma'lumotini faqat O'QIYDI,
--                     platforma jadvallariga yozadi; shaxsiy AI suhbatlari
--                     va OTP kodlarini ko'rmaydi
--
-- Hech bir rol: jadval yarata/o'chira olmaydi (DDL — faqat migratsiya
-- egasi), audit jurnalini o'zgartira yoki o'chira olmaydi (append-only).
--
-- Rollarning o'zi bu yerda YARATILMAYDI (parollar repoga tushmasin):
--   production — scripts/db/app-roles.sql (bir marta, DBA)
--   dev        — db/seed/V902__dev_app_roles.sql (avtomatik)
-- Rol hali yo'q bo'lsa, u uchun huquq berish o'tkazib yuboriladi; rol keyin
-- yaratilsa, app-roles.sql oxirida `SELECT farzandim_apply_grants()` chaqiriladi.
--
-- YANGI JADVAL qo'shilganda: quyidagi ro'yxatlardan biriga qo'shing. Ro'yxatda
-- yo'q jadval standart toifaga tushadi: school — yozadi, admin — o'qiydi,
-- mobile — ko'rmaydi.
--
-- Fayl o'zgarsa, Flyway uni keyingi migratsiyada qayta ishga tushiradi.
-- =============================================================================

CREATE OR REPLACE FUNCTION farzandim_grant(role_name text, privileges text, tables text[])
RETURNS void
LANGUAGE plpgsql AS $fn$
DECLARE
    t text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        RETURN;
    END IF;
    FOREACH t IN ARRAY tables LOOP
        IF to_regclass(format('public.%I', t)) IS NOT NULL THEN
            EXECUTE format('GRANT %s ON public.%I TO %I', privileges, t, role_name);
        END IF;
    END LOOP;
END
$fn$;

CREATE OR REPLACE FUNCTION farzandim_apply_grants()
RETURNS void
LANGUAGE plpgsql AS $fn$
DECLARE
    app_roles constant text[] := ARRAY['farzandim_school', 'farzandim_mobile', 'farzandim_admin'];

    -- Maktab ichidagi o'quv ma'lumoti — CRM yuritadi
    academic constant text[] := ARRAY[
        'subjects', 'topics', 'teachers', 'groups', 'students', 'parents', 'student_parents',
        'teacher_assignments', 'schedule_slots', 'lessons', 'grades', 'attendance',
        'homework', 'homework_variants', 'homework_submissions', 'assessment_tasks',
        'skill_assessments', 'interests', 'cognitive_recommendations', 'student_performance',
        'achievements'];

    -- Maktab ichidagi aloqa va AI (CRM yozadi)
    school_ops constant text[] := ARRAY[
        'ai_conversations', 'ai_messages', 'ai_jobs', 'ai_usage_daily', 'ai_embeddings',
        'messages', 'message_recipients', 'notifications', 'files',
        'device_tokens', 'refresh_tokens'];

    -- Yetkazib berish jurnali — uchala servis SMS/email yuboradi
    delivery constant text[] := ARRAY['sms_messages', 'email_messages'];

    -- Platforma jadvallari — faqat admin boshqaradi
    platform constant text[] := ARRAY['school_subscriptions', 'school_notes'];
    settings constant text[] := ARRAY['subscription_plans', 'feature_flags'];

    known text[];
    r text;
    t record;
BEGIN
    known := academic || school_ops || delivery || platform || settings
        || ARRAY['schools', 'users', 'otp_codes', 'audit_log', 'flyway_schema_history'];

    -- 0. Toza holat: avvalgi huquqlar bekor qilinadi, keyin matritsa qo'llanadi
    FOREACH r IN ARRAY app_roles LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', r);
            -- Ustun darajasidagi huquqlar "ALL TABLES" bilan bekor bo'lmaydi
            IF to_regclass('public.audit_log') IS NOT NULL THEN
                EXECUTE format('REVOKE SELECT (id) ON public.audit_log FROM %I', r);
            END IF;
            EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', r);
            -- audit_log.id (bigserial) va kelajakdagi ketma-ketliklar
            EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', r);
        END IF;
    END LOOP;

    -- Uchala servis ishga tushishda migratsiya holatini tekshiradi (validate-at-start)
    PERFORM farzandim_grant(app_role, 'SELECT', ARRAY['flyway_schema_history'])
    FROM unnest(app_roles) AS app_role;

    -- 1. school ------------------------------------------------------------
    PERFORM farzandim_grant('farzandim_school', 'SELECT, INSERT, UPDATE, DELETE', academic || school_ops);
    PERFORM farzandim_grant('farzandim_school', 'SELECT, INSERT, UPDATE', ARRAY['users'] || delivery);
    PERFORM farzandim_grant('farzandim_school', 'SELECT, UPDATE', ARRAY['schools']);
    PERFORM farzandim_grant('farzandim_school', 'SELECT', settings);
    -- Audit: faqat yozadi, o'qiy olmaydi (id ketma-ketlikdan — AuditEntry izohi)
    PERFORM farzandim_grant('farzandim_school', 'INSERT', ARRAY['audit_log']);

    -- 2. mobile ------------------------------------------------------------
    PERFORM farzandim_grant('farzandim_mobile', 'SELECT', academic || ARRAY['schools', 'messages'] || settings);
    PERFORM farzandim_grant('farzandim_mobile', 'INSERT', ARRAY['parents']);          -- mustaqil ota-ona
    PERFORM farzandim_grant('farzandim_mobile', 'UPDATE', ARRAY[
        'homework_submissions',          -- "bajarildi" tasdig'i
        'cognitive_recommendations']);   -- tavsiya holati
    PERFORM farzandim_grant('farzandim_mobile', 'SELECT, INSERT, UPDATE', ARRAY['users']);
    PERFORM farzandim_grant('farzandim_mobile', 'SELECT, UPDATE', ARRAY['notifications', 'message_recipients']);
    PERFORM farzandim_grant('farzandim_mobile', 'SELECT, INSERT, UPDATE, DELETE',
        ARRAY['refresh_tokens', 'otp_codes', 'device_tokens']);
    PERFORM farzandim_grant('farzandim_mobile', 'SELECT, INSERT, UPDATE',
        ARRAY['ai_conversations', 'ai_messages', 'ai_jobs', 'ai_usage_daily'] || delivery);

    -- 3. admin -------------------------------------------------------------
    -- O'qish: hamma narsa, SHAXSIY AI suhbatlari, embedding va OTP kodlaridan tashqari
    PERFORM farzandim_grant('farzandim_admin', 'SELECT', academic || ARRAY[
        'ai_jobs', 'ai_usage_daily', 'messages', 'message_recipients', 'notifications', 'files',
        'device_tokens'] || settings);
    PERFORM farzandim_grant('farzandim_admin', 'SELECT, INSERT, UPDATE',
        ARRAY['schools', 'users', 'school_subscriptions', 'refresh_tokens'] || delivery);
    PERFORM farzandim_grant('farzandim_admin', 'UPDATE', ARRAY['ai_jobs'] || settings);  -- retry, tarif, bayroq
    PERFORM farzandim_grant('farzandim_admin', 'INSERT', ARRAY['notifications']);        -- e'lonlar
    PERFORM farzandim_grant('farzandim_admin', 'UPDATE, DELETE', ARRAY['device_tokens']); -- o'lik push tokenlari
    PERFORM farzandim_grant('farzandim_admin', 'SELECT, INSERT, DELETE', ARRAY['school_notes']);
    PERFORM farzandim_grant('farzandim_admin', 'SELECT, INSERT', ARRAY['audit_log']);

    -- 4. Ro'yxatda yo'q jadvallar (yangi migratsiyalar) — standart toifa
    FOR t IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'public' AND NOT (tablename = ANY (known))
    LOOP
        PERFORM farzandim_grant('farzandim_school', 'SELECT, INSERT, UPDATE, DELETE', ARRAY[t.tablename]);
        PERFORM farzandim_grant('farzandim_admin', 'SELECT', ARRAY[t.tablename]);
    END LOOP;

    -- 5. Kelajakdagi jadvallar ham standart toifani darhol olsin (migratsiya
    --    egasi yaratgan obyektlar uchun) — R__ qayta ishlaguncha kutmasdan
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'farzandim_school') THEN
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO farzandim_school;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO farzandim_school;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'farzandim_admin') THEN
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO farzandim_admin;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO farzandim_admin;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'farzandim_mobile') THEN
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO farzandim_mobile;
    END IF;
END
$fn$;

-- Servis rollari bu funksiyalarni chaqira olmasin
REVOKE ALL ON FUNCTION farzandim_grant(text, text, text[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION farzandim_apply_grants() FROM PUBLIC;

SELECT farzandim_apply_grants();
