-- =============================================================================
-- Farzandim AI — V7: yetkazib berish kanallari (SMS va push)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- SMS hisobi.
--
-- SMS — pullik kanal, shuning uchun har bir yuborish qayd etiladi: kim uchun,
-- qaysi provayder orqali, nechta qismga bo'lindi, yetib bordimi.
--
-- MUHIM: xabar MATNI bu yerda saqlanmaydi. OTP kodi va yangi parol SMS ichida
-- ketadi — ularni bazaga yozish parolni ochiq saqlash bilan barobar.
-- Faqat metama'lumot qoladi.
-- -----------------------------------------------------------------------------
CREATE TABLE sms_messages (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid REFERENCES schools (id) ON DELETE SET NULL,
    user_id     uuid REFERENCES users (id) ON DELETE SET NULL,
    provider    text        NOT NULL,
    recipient   text        NOT NULL,
    purpose     text        NOT NULL
                            CHECK (purpose IN ('OTP', 'CREDENTIALS', 'ANNOUNCEMENT', 'TEST')),
    parts       integer     NOT NULL DEFAULT 1,
    status      text        NOT NULL DEFAULT 'QUEUED'
                            CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'REJECTED')),
    provider_message_id text,
    error_code  text,
    error_message text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz,
    delivered_at timestamptz
);

CREATE INDEX sms_messages_recipient_idx ON sms_messages (recipient, created_at DESC);
CREATE INDEX sms_messages_school_idx    ON sms_messages (school_id, created_at DESC);
CREATE INDEX sms_messages_status_idx    ON sms_messages (status, created_at DESC);
-- Provayder callback'i xabarni o'z identifikatori bo'yicha topadi
CREATE UNIQUE INDEX sms_messages_provider_id_key ON sms_messages (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- Push qurilmalari: nosoz tokenni darhol o'chirmaymiz.
--
-- FCM "UNREGISTERED" desa — ilova o'chirilgan, token o'lik: o'chiramiz.
-- Vaqtinchalik xato (UNAVAILABLE, INTERNAL) bo'lsa — sanaymiz va bir necha
-- marta ketma-ket muvaffaqiyatsiz bo'lgandagina o'chiramiz. Aks holda FCM'ning
-- bir soatlik uzilishi butun bazadagi tokenlarni yo'q qilib yuborardi.
-- -----------------------------------------------------------------------------
ALTER TABLE device_tokens ADD COLUMN failure_count integer NOT NULL DEFAULT 0;
ALTER TABLE device_tokens ADD COLUMN disabled_at   timestamptz;
ALTER TABLE device_tokens ADD COLUMN last_error    text;

CREATE INDEX device_tokens_active_idx ON device_tokens (user_id) WHERE disabled_at IS NULL;
