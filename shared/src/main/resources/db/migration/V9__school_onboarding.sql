-- =============================================================================
-- Farzandim AI — V9: maktabni shartnoma asosida ulash (super admin paneli)
--
--   • schools        — yuridik rekvizitlar va shartnoma
--   • users          — maktab adminlarining darajasi (superadmin / admin)
--   • school_subscriptions — to'lov holati
--   • school_notes   — platforma jamoasining ichki qaydlari (qo'ng'iroq, uchrashuv)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Maktab: yuridik shaxs va shartnoma
-- -----------------------------------------------------------------------------
ALTER TABLE schools
    ADD COLUMN legal_name         text NOT NULL DEFAULT '',
    ADD COLUMN tin                text,                          -- STIR (INN)
    ADD COLUMN director_name      text NOT NULL DEFAULT '',
    ADD COLUMN region             text NOT NULL DEFAULT '',
    ADD COLUMN city               text NOT NULL DEFAULT '',
    ADD COLUMN contact_person     text NOT NULL DEFAULT '',
    ADD COLUMN contact_phone      text NOT NULL DEFAULT '',
    ADD COLUMN contract_number    text,
    ADD COLUMN contract_signed_on date,
    ADD COLUMN status_reason      text;

-- Bir xil STIR bilan ikki maktab — deyarli har doim takroriy kiritish xatosi.
CREATE UNIQUE INDEX schools_tin_key ON schools (tin) WHERE tin IS NOT NULL AND tin <> '';
CREATE INDEX schools_status_idx ON schools (status, subscription_until);

-- -----------------------------------------------------------------------------
-- Maktab adminlari darajasi
--
--   SUPER   — maktab superadmini (ta'sischi / direktor): boshqa adminlarni
--             qo'shadi, bloklaydi, darajasini o'zgartiradi
--   REGULAR — oddiy admin: CRM'ning qolgan hamma qismi
--
-- Rol (`role`) o'zgarmaydi — SCHOOL_ADMIN. Daraja tokenga qo'shimcha guruh
-- sifatida tushadi (SCHOOL_SUPER_ADMIN), shuning uchun mavjud
-- @RolesAllowed(SCHOOL_ADMIN) endpointlari ikkalasida ham ishlaydi.
-- -----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN admin_level text CHECK (admin_level IN ('SUPER', 'REGULAR'));

ALTER TABLE users
    ADD CONSTRAINT users_admin_level_role_chk
        CHECK (admin_level IS NULL OR role = 'SCHOOL_ADMIN');

-- Mavjud adminlar shu paytgacha to'liq huquqqa ega edi — xatti-harakat saqlanadi.
UPDATE users SET admin_level = 'SUPER' WHERE role = 'SCHOOL_ADMIN';

-- -----------------------------------------------------------------------------
-- Obuna to'lovi
--
-- Obuna muddati (status) va to'lov (payment_status) — alohida narsalar:
-- maktab shartnoma bo'yicha ishlay boshlaydi, to'lov esa keyinroq keladi.
-- -----------------------------------------------------------------------------
ALTER TABLE school_subscriptions
    ADD COLUMN payment_status    text NOT NULL DEFAULT 'UNPAID'
                                 CHECK (payment_status IN ('UNPAID', 'PAID', 'WAIVED')),
    ADD COLUMN paid_on           date,
    ADD COLUMN payment_method    text,
    ADD COLUMN payment_reference text,
    ADD COLUMN discount_percent  integer NOT NULL DEFAULT 0 CHECK (discount_percent BETWEEN 0 AND 100),
    ADD COLUMN cancelled_at      timestamptz;

-- Bu migratsiyadan oldingi obunalarda to'lov hisobi yuritilmagan —
-- ularni qarz sifatida ko'rsatmaymiz.
UPDATE school_subscriptions SET payment_status = 'PAID', paid_on = starts_on;

CREATE INDEX school_subscriptions_payment_idx ON school_subscriptions (payment_status, starts_on DESC);

-- -----------------------------------------------------------------------------
-- Ichki qaydlar — maktab bilan ishlash tarixi (faqat platforma jamoasi ko'radi)
-- -----------------------------------------------------------------------------
CREATE TABLE school_notes (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id  uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    author_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    kind       text        NOT NULL DEFAULT 'NOTE'
                           CHECK (kind IN ('NOTE', 'CALL', 'MEETING', 'CONTRACT', 'SUPPORT')),
    body       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX school_notes_school_idx ON school_notes (school_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- Audit: panel amallar bo'yicha filtrlaydi
-- -----------------------------------------------------------------------------
CREATE INDEX audit_log_created_idx ON audit_log (created_at DESC);
CREATE INDEX audit_log_action_idx  ON audit_log (action, created_at DESC);

-- Tarif imkoniyatlari endi haqiqatan tekshiriladi (PlanAccess) — BASIC'da
-- AI o'chiq ekanini aniq yozib qo'yamiz.
UPDATE subscription_plans
SET features = features || '{"ai": false, "aiChat": false, "aiSkillAnalysis": false, "vectorSearch": false}'::jsonb
WHERE code = 'BASIC';
