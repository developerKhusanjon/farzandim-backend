-- =============================================================================
-- Farzandim AI — V1: kengaytmalar, maktab, identifikatsiya
-- Manba: docs/02-data-model.md
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), digest()
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- oddiy qidiruv (LIKE '%...%')
CREATE EXTENSION IF NOT EXISTS vector;     -- pgvector — AI semantik qidiruv

-- -----------------------------------------------------------------------------
-- Maktab (tenant)
-- -----------------------------------------------------------------------------
CREATE TABLE schools (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name               text        NOT NULL,
    short_name         text        NOT NULL,
    logo_url           text,
    address            text        NOT NULL DEFAULT '',
    phone              text        NOT NULL DEFAULT '',
    email              text        NOT NULL DEFAULT '',
    academic_year      text        NOT NULL,
    grading_system     text        NOT NULL DEFAULT 'HUNDRED'
                                   CHECK (grading_system IN ('FIVE', 'HUNDRED', 'LETTER')),
    locale             text        NOT NULL DEFAULT 'uz' CHECK (locale IN ('uz', 'ru', 'en')),
    timezone           text        NOT NULL DEFAULT 'Asia/Tashkent',
    subscription_plan  text        NOT NULL DEFAULT 'BASIC'
                                   CHECK (subscription_plan IN ('BASIC', 'STANDARD', 'PREMIUM')),
    subscription_until date,
    status             text        NOT NULL DEFAULT 'ACTIVE'
                                   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    settings           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    ai_daily_limit     integer     NOT NULL DEFAULT 200,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX schools_short_name_key ON schools (lower(short_name));

-- -----------------------------------------------------------------------------
-- Foydalanuvchi — barcha rollar uchun yagona jadval
-- school_id = NULL  →  SUPER_ADMIN yoki mustaqil ota-ona (B2C)
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     uuid REFERENCES schools (id) ON DELETE CASCADE,
    role          text        NOT NULL
                              CHECK (role IN ('SUPER_ADMIN', 'SCHOOL_ADMIN', 'TEACHER', 'PARENT', 'STUDENT')),
    first_name    text        NOT NULL,
    last_name     text        NOT NULL,
    middle_name   text,
    avatar_url    text,
    username      text,
    email         text,
    phone         text,
    password_hash text,
    status        text        NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'INVITED', 'BLOCKED')),
    locale        text        NOT NULL DEFAULT 'uz' CHECK (locale IN ('uz', 'ru', 'en')),
    must_change_password boolean NOT NULL DEFAULT false,
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Login butun platformada unikal.
--
-- Nega maktab ichida emas: kirish formasida bitta maydon bor — foydalanuvchi
-- maktabni tanlamaydi. Agar ikki maktabda bir xil login bo'lsa, tizim kimni
-- kiritishni bilmaydi. Login generatsiyasi band bo'lsa oxiriga raqam qo'shadi
-- (Credentials.suggestUsername).
CREATE UNIQUE INDEX users_username_key ON users (lower(username)) WHERE username IS NOT NULL;
CREATE UNIQUE INDEX users_email_key    ON users (lower(email))               WHERE email IS NOT NULL;
CREATE UNIQUE INDEX users_phone_key    ON users (phone)                      WHERE phone IS NOT NULL;
CREATE INDEX users_school_role_idx     ON users (school_id, role, status);

-- -----------------------------------------------------------------------------
-- Refresh token — rotatsiya + server tomonda bekor qilish
-- Xotirada faqat SHA-256 hash saqlanadi, tokenning o'zi hech qachon yozilmaydi.
-- Bitta "family" = bitta qurilmadagi bitta login sessiyasi.
-- Eski token qayta ishlatilsa (reuse) — butun family bekor qilinadi.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   uuid        NOT NULL,
    token_hash  text        NOT NULL,
    audience    text        NOT NULL,   -- school | mobile | admin
    device_info text,
    ip_address  inet,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    revoke_reason text,
    replaced_by uuid REFERENCES refresh_tokens (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX refresh_tokens_hash_key ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_family_idx      ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx        ON refresh_tokens (user_id, revoked_at);
CREATE INDEX refresh_tokens_expiry_idx      ON refresh_tokens (expires_at);

-- -----------------------------------------------------------------------------
-- OTP — mustaqil ota-ona email/telefon orqali kiradi
-- Kod hash holida; urinishlar soni cheklangan.
-- -----------------------------------------------------------------------------
CREATE TABLE otp_codes (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel    text        NOT NULL CHECK (channel IN ('PHONE', 'EMAIL')),
    recipient  text        NOT NULL,
    code_hash  text        NOT NULL,
    purpose    text        NOT NULL DEFAULT 'LOGIN'
                           CHECK (purpose IN ('LOGIN', 'REGISTER', 'PASSWORD_RESET')),
    attempts   integer     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX otp_codes_lookup_idx ON otp_codes (recipient, purpose, expires_at DESC);

-- -----------------------------------------------------------------------------
-- Push qurilmalari (FCM/APNs)
-- -----------------------------------------------------------------------------
CREATE TABLE device_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      text        NOT NULL,
    platform   text        NOT NULL CHECK (platform IN ('IOS', 'ANDROID', 'WEB')),
    locale     text        NOT NULL DEFAULT 'uz',
    app_version text,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX device_tokens_token_key ON device_tokens (token);
CREATE INDEX device_tokens_user_idx         ON device_tokens (user_id);

-- -----------------------------------------------------------------------------
-- Audit — kim nimani o'zgartirdi (super admin paneli uchun ham)
-- -----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id         bigserial PRIMARY KEY,
    school_id  uuid REFERENCES schools (id) ON DELETE SET NULL,
    actor_id   uuid REFERENCES users (id) ON DELETE SET NULL,
    actor_role text,
    action     text        NOT NULL,
    entity_type text       NOT NULL,
    entity_id  text,
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    ip_address inet,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_school_idx ON audit_log (school_id, created_at DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, created_at DESC);
