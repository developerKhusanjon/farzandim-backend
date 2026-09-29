-- =============================================================================
-- Farzandim AI — V8: email kanali
--
-- SMS bilan bir xil mantiq: xabar MATNI saqlanmaydi (OTP kodi va parol shu
-- matnda bo'ladi), faqat metama'lumot qoladi. Email arzon, lekin "ota-onaga
-- hisob ma'lumotlari yuborildimi?" degan savol baribir javob talab qiladi.
-- =============================================================================

CREATE TABLE email_messages (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid REFERENCES schools (id) ON DELETE SET NULL,
    user_id     uuid REFERENCES users (id) ON DELETE SET NULL,
    provider    text        NOT NULL,
    recipient   text        NOT NULL,
    subject     text        NOT NULL DEFAULT '',
    purpose     text        NOT NULL
                            CHECK (purpose IN ('OTP', 'CREDENTIALS', 'ANNOUNCEMENT', 'TEST')),
    status      text        NOT NULL DEFAULT 'QUEUED'
                            CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'REJECTED')),
    error_code  text,
    error_message text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz
);

CREATE INDEX email_messages_recipient_idx ON email_messages (lower(recipient), created_at DESC);
CREATE INDEX email_messages_school_idx    ON email_messages (school_id, created_at DESC);
CREATE INDEX email_messages_status_idx    ON email_messages (status, created_at DESC);
