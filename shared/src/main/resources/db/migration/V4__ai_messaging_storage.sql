-- =============================================================================
-- Farzandim AI — V4: AI, job navbati, embedding, xabarlar, fayllar
-- =============================================================================

-- -----------------------------------------------------------------------------
-- AI suhbat (chat yordamchisi)
-- -----------------------------------------------------------------------------
CREATE TABLE ai_conversations (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id    uuid REFERENCES schools (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title        text        NOT NULL DEFAULT '',
    context_type text        NOT NULL DEFAULT 'GENERAL'
                             CHECK (context_type IN ('GENERAL', 'STUDENT', 'LESSON', 'HOMEWORK', 'REPORT')),
    context_id   uuid,
    archived     boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_conversations_user_idx ON ai_conversations (user_id, updated_at DESC);

CREATE TABLE ai_messages (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid        NOT NULL REFERENCES ai_conversations (id) ON DELETE CASCADE,
    author          text        NOT NULL CHECK (author IN ('USER', 'AI')),
    content         text        NOT NULL,
    suggestions     text[]      NOT NULL DEFAULT '{}',
    terms           text[]      NOT NULL DEFAULT '{}',
    model           text,
    input_tokens    integer,
    output_tokens   integer,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_messages_conversation_idx ON ai_messages (conversation_id, created_at);

-- -----------------------------------------------------------------------------
-- AI job navbati — barcha og'ir AI ishlari shu yerdan o'tadi.
-- Valkey emas, PostgreSQL: job holati tranzaksion va tiklanadigan bo'lishi kerak.
-- Worker `FOR UPDATE SKIP LOCKED` bilan oladi — bir nechta pod xavfsiz ishlaydi.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_jobs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     uuid REFERENCES schools (id) ON DELETE CASCADE,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    type          text        NOT NULL,
    status        text        NOT NULL DEFAULT 'QUEUED'
                              CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    priority      integer     NOT NULL DEFAULT 100,
    entity_type   text,
    entity_id     uuid,
    idempotency_key text,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    result        jsonb,
    error_code    text,
    error_message text,
    attempts      integer     NOT NULL DEFAULT 0,
    max_attempts  integer     NOT NULL DEFAULT 3,
    locked_by     text,
    locked_at     timestamptz,
    run_after     timestamptz NOT NULL DEFAULT now(),
    started_at    timestamptz,
    finished_at   timestamptz,
    model         text,
    input_tokens  integer,
    output_tokens integer,
    cost_micros   bigint,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Navbatni olish uchun asosiy indeks
CREATE INDEX ai_jobs_queue_idx ON ai_jobs (status, run_after, priority)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ai_jobs_school_idx ON ai_jobs (school_id, created_at DESC);
CREATE INDEX ai_jobs_entity_idx ON ai_jobs (entity_type, entity_id);
CREATE UNIQUE INDEX ai_jobs_idempotency_key ON ai_jobs (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Kunlik AI sarfi — limit va hisob-kitob uchun
CREATE TABLE ai_usage_daily (
    school_id     uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    usage_date    date        NOT NULL,
    feature       text        NOT NULL,
    calls         integer     NOT NULL DEFAULT 0,
    input_tokens  bigint      NOT NULL DEFAULT 0,
    output_tokens bigint      NOT NULL DEFAULT 0,
    cost_micros   bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (school_id, usage_date, feature)
);

CREATE INDEX ai_usage_daily_date_idx ON ai_usage_daily (usage_date DESC);

-- -----------------------------------------------------------------------------
-- pgvector — semantik qidiruv ("kechikkan yetkazib berishdan shikoyat qilganlar")
-- Dimension konfiguratsiyadagi embedding modeliga mos bo'lishi shart.
-- Standart: voyage-3 / voyage-3-lite → 1024.
-- -----------------------------------------------------------------------------
CREATE TABLE ai_embeddings (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    entity_type text        NOT NULL,   -- LESSON | HOMEWORK | SKILL_ASSESSMENT | MESSAGE | NOTE
    entity_id   uuid        NOT NULL,
    student_id  uuid REFERENCES students (id) ON DELETE CASCADE,
    chunk_index integer     NOT NULL DEFAULT 0,
    content     text        NOT NULL,
    embedding   vector(1024) NOT NULL,
    model       text        NOT NULL,
    metadata    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ai_embeddings_entity_key ON ai_embeddings (entity_type, entity_id, chunk_index);
CREATE INDEX ai_embeddings_school_idx        ON ai_embeddings (school_id, entity_type);
CREATE INDEX ai_embeddings_student_idx       ON ai_embeddings (student_id) WHERE student_id IS NOT NULL;

-- HNSW — kosinus masofa bo'yicha. Kichik jadvalda ham to'g'ri ishlaydi,
-- ivfflat'dan farqli o'laroq oldindan "train" qilish shart emas.
CREATE INDEX ai_embeddings_vector_idx ON ai_embeddings
    USING hnsw (embedding vector_cosine_ops);

-- -----------------------------------------------------------------------------
-- Xabarlar va bildirishnomalar
-- -----------------------------------------------------------------------------
CREATE TABLE messages (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id      uuid REFERENCES schools (id) ON DELETE CASCADE,
    sender_id      uuid REFERENCES users (id) ON DELETE SET NULL,
    sender_role    text,
    recipient_type text        NOT NULL
                               CHECK (recipient_type IN ('PARENT', 'GROUP_PARENTS', 'TEACHER', 'ALL')),
    recipient_group_id uuid REFERENCES groups (id) ON DELETE SET NULL,
    subject        text        NOT NULL DEFAULT '',
    body           text        NOT NULL,
    category       text        NOT NULL DEFAULT 'GENERAL'
                               CHECK (category IN ('GRADE', 'HOMEWORK', 'ATTENDANCE', 'ANNOUNCEMENT',
                                                   'LESSON_SUMMARY', 'AI', 'GENERAL')),
    related_entity_type text,
    related_entity_id   uuid,
    attachments    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    sent_at        timestamptz NOT NULL DEFAULT now(),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX messages_school_idx ON messages (school_id, sent_at DESC);

CREATE TABLE message_recipients (
    message_id uuid        NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id uuid REFERENCES students (id) ON DELETE CASCADE,
    read_at    timestamptz,
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX message_recipients_user_idx ON message_recipients (user_id, read_at);

CREATE TABLE notifications (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id  uuid REFERENCES schools (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       text        NOT NULL DEFAULT 'GENERAL',
    title      text        NOT NULL,
    body       text        NOT NULL DEFAULT '',
    color_key  text        NOT NULL DEFAULT 'blue',
    icon_key   text        NOT NULL DEFAULT 'bell',
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    link       text,
    is_read    boolean     NOT NULL DEFAULT false,
    push_sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notifications_user_idx ON notifications (user_id, is_read, created_at DESC);

-- -----------------------------------------------------------------------------
-- Fayl metama'lumoti — baytlar DigitalOcean Spaces'da, bu yerda faqat metadata.
-- Oqim: presign → brauzer to'g'ridan-to'g'ri Spaces'ga yuklaydi → confirm.
-- -----------------------------------------------------------------------------
CREATE TABLE files (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     uuid REFERENCES schools (id) ON DELETE CASCADE,
    owner_id      uuid REFERENCES users (id) ON DELETE SET NULL,
    bucket        text        NOT NULL,
    object_key    text        NOT NULL,
    original_name text        NOT NULL,
    content_type  text        NOT NULL,
    size_bytes    bigint      NOT NULL DEFAULT 0,
    checksum      text,
    purpose       text        NOT NULL DEFAULT 'GENERAL',   -- AVATAR | HOMEWORK | LESSON_MATERIAL | SUBMISSION | REPORT
    entity_type   text,
    entity_id     uuid,
    status        text        NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'READY', 'FAILED', 'DELETED')),
    scan_status   text        NOT NULL DEFAULT 'SKIPPED'
                              CHECK (scan_status IN ('SKIPPED', 'PENDING', 'CLEAN', 'INFECTED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    confirmed_at  timestamptz,
    deleted_at    timestamptz
);

CREATE UNIQUE INDEX files_object_key_key ON files (bucket, object_key);
CREATE INDEX files_school_idx            ON files (school_id, created_at DESC);
CREATE INDEX files_entity_idx            ON files (entity_type, entity_id) WHERE status = 'READY';
-- Tasdiqlanmagan yuklamalarni tozalash uchun
CREATE INDEX files_pending_idx           ON files (created_at) WHERE status = 'PENDING';
