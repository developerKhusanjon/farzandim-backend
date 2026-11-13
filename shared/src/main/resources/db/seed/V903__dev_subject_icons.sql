-- =============================================================================
-- Dev seed: fanlarning ikonkasi.
-- V900/V901 fanlarni ustunning standart qiymati (`book`) bilan yaratgan — web va
-- mobil ilovada hamma fan bir xil ikonka bilan chiqardi. Kalitlar web'dagi
-- SubjectIcon ro'yxati (lucide nomlari) bilan bir xil.
-- Faqat dev (db/seed) — production'da ikonkani maktab fan yaratganda tanlaydi.
-- =============================================================================
UPDATE subjects
SET icon_key = CASE name
        WHEN 'Matematika'       THEN 'calculator'
        WHEN 'Ingliz tili'      THEN 'languages'
        WHEN 'Ona tili'         THEN 'book-open'
        WHEN 'Informatika'      THEN 'code'
        WHEN 'Tabiatshunoslik'  THEN 'flask-conical'
        WHEN 'Jismoniy tarbiya' THEN 'activity'
        ELSE icon_key
    END
WHERE icon_key = 'book';
