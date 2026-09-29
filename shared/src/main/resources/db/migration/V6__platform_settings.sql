-- =============================================================================
-- Farzandim AI — V6: platforma sozlamalari (super admin paneli uchun)
-- Admin uchun front hali yozilmagan — backend shartnomasi oldindan tayyor.
-- =============================================================================

-- Tarif rejalari: narx va limitlar kodda emas, bazada. Admin paneldan o'zgaradi.
CREATE TABLE subscription_plans (
    code              text PRIMARY KEY CHECK (code IN ('BASIC', 'STANDARD', 'PREMIUM')),
    title             text        NOT NULL,
    price_per_student_uzs bigint  NOT NULL DEFAULT 0,
    min_monthly_uzs   bigint      NOT NULL DEFAULT 0,
    max_students      integer,
    ai_daily_limit    integer     NOT NULL DEFAULT 0,
    features          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    active            boolean     NOT NULL DEFAULT true,
    updated_at        timestamptz NOT NULL DEFAULT now()
);

INSERT INTO subscription_plans (code, title, price_per_student_uzs, min_monthly_uzs, ai_daily_limit, features) VALUES
    ('BASIC',    'Basic — asosiy CRM',        12000,  600000,   0,
     '{"ai": false, "reports": true, "mobileApp": true, "aiChat": false}'::jsonb),
    ('STANDARD', 'Standard — cheklangan AI',  18000,  900000, 200,
     '{"ai": true, "reports": true, "mobileApp": true, "aiChat": true, "aiSkillAnalysis": false}'::jsonb),
    ('PREMIUM',  'Premium — to''liq AI',      25000, 1500000, 1000,
     '{"ai": true, "reports": true, "mobileApp": true, "aiChat": true, "aiSkillAnalysis": true, "vectorSearch": true}'::jsonb);

-- Obuna tarixi — to'lov va tarif o'zgarishi
CREATE TABLE school_subscriptions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id    uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    plan_code    text        NOT NULL REFERENCES subscription_plans (code),
    starts_on    date        NOT NULL,
    ends_on      date        NOT NULL,
    student_count integer    NOT NULL DEFAULT 0,
    amount_uzs   bigint      NOT NULL DEFAULT 0,
    status       text        NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'CANCELLED')),
    note         text,
    created_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX school_subscriptions_school_idx ON school_subscriptions (school_id, starts_on DESC);

-- Platforma darajasidagi bayroqlar (maktab bo'yicha override qilinadi)
CREATE TABLE feature_flags (
    key         text PRIMARY KEY,
    description text        NOT NULL DEFAULT '',
    enabled     boolean     NOT NULL DEFAULT false,
    school_overrides jsonb  NOT NULL DEFAULT '{}'::jsonb,   -- {"<schoolId>": true}
    updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO feature_flags (key, description, enabled) VALUES
    ('ai.vector_search',    'Semantik qidiruv (pgvector)',            false),
    ('ai.auto_summary',     'Dars tugagach xulosani avtomatik tuzish', true),
    ('parent.independent',  'Mustaqil ota-ona ro''yxatdan o''tishi',    true),
    ('files.virus_scan',    'Yuklangan fayllarni tekshirish',          false);
