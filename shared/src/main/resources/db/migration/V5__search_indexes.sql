-- =============================================================================
-- Farzandim AI — V5: oddiy qidiruv uchun trigram indekslar (pg_trgm)
--
-- B-tree `LIKE '%abc%'` shablonida deyarli yordam bermaydi. Shuning uchun
-- ism/telefon/email bo'yicha substring qidiruv GIN + gin_trgm_ops ustida ketadi.
--
-- MUHIM: bu — ODDIY qidiruv (ism, email, telefon, guruh).
-- Semantik qidiruv (`ai_embeddings`, pgvector) undan butunlay alohida turadi —
-- ikkalasini aralashtirmang. AI qidiruv oldin filtrlarni qo'yadi,
-- keyin vektor natijalarini shu filtr ichida qidiradi.
-- =============================================================================

-- --- O'quvchilar --------------------------------------------------------------
CREATE INDEX students_first_name_trgm_idx ON students USING gin (lower(first_name) gin_trgm_ops);
CREATE INDEX students_last_name_trgm_idx  ON students USING gin (lower(last_name) gin_trgm_ops);

-- Bitta so'rovda "ism + familiya + otasining ismi" bo'yicha qidirish uchun
ALTER TABLE students
    ADD COLUMN search_text text GENERATED ALWAYS AS (
        lower(coalesce(last_name, '') || ' ' || coalesce(first_name, '') || ' ' || coalesce(middle_name, ''))
    ) STORED;

CREATE INDEX students_search_trgm_idx ON students USING gin (search_text gin_trgm_ops);

-- --- Foydalanuvchilar (o'qituvchi, ota-ona, admin) -----------------------------
CREATE INDEX users_first_name_trgm_idx ON users USING gin (lower(first_name) gin_trgm_ops);
CREATE INDEX users_last_name_trgm_idx  ON users USING gin (lower(last_name) gin_trgm_ops);
CREATE INDEX users_email_trgm_idx      ON users USING gin (lower(coalesce(email, '')) gin_trgm_ops);
CREATE INDEX users_phone_trgm_idx      ON users USING gin (coalesce(phone, '') gin_trgm_ops);
CREATE INDEX users_username_trgm_idx   ON users USING gin (lower(coalesce(username, '')) gin_trgm_ops);

ALTER TABLE users
    ADD COLUMN search_text text GENERATED ALWAYS AS (
        lower(coalesce(last_name, '') || ' ' || coalesce(first_name, '') || ' ' ||
              coalesce(middle_name, '') || ' ' || coalesce(username, '') || ' ' ||
              coalesce(email, '') || ' ' || coalesce(phone, ''))
    ) STORED;

CREATE INDEX users_search_trgm_idx ON users USING gin (search_text gin_trgm_ops);

-- --- Guruh, fan, mavzu, vazifa ------------------------------------------------
CREATE INDEX groups_name_trgm_idx    ON groups   USING gin (lower(name) gin_trgm_ops);
CREATE INDEX subjects_name_trgm_idx  ON subjects USING gin (lower(name) gin_trgm_ops);
CREATE INDEX topics_title_trgm_idx   ON topics   USING gin (lower(title) gin_trgm_ops);
CREATE INDEX homework_title_trgm_idx ON homework USING gin (lower(title) gin_trgm_ops);
CREATE INDEX lessons_title_trgm_idx  ON lessons  USING gin (lower(title) gin_trgm_ops);
