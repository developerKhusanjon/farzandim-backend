-- =============================================================================
-- Dev seed: jadvaldagi o'qituvchi = guruh+fanga biriktirilgan o'qituvchi.
-- V900 slot o'qituvchisini biriktirishdan boshqa formula bilan tanlagan edi —
-- 100 ta slotdan 62 tasida o'qituvchi o'sha guruh+fanga biriktirilmagan. Natija:
-- o'qituvchi o'z jadvalidagi darsga baho qo'ya olmas, uy vazifasi bera olmas edi
-- ("Bu guruh va fan sizga biriktirilmagan"). V900 o'zgartirilmaydi (checksum).
-- Faqat dev (db/seed).
-- =============================================================================
UPDATE schedule_slots ss
SET teacher_id = ta.teacher_id
FROM teacher_assignments ta
WHERE ta.school_id = ss.school_id
  AND ta.group_id = ss.group_id
  AND ta.subject_id = ss.subject_id
  AND ta.teacher_id <> ss.teacher_id;

-- Jadvaldan yaratilgan darslar slotning o'qituvchisiga ergashadi
UPDATE lessons l
SET teacher_id = ss.teacher_id
FROM schedule_slots ss
WHERE l.schedule_slot_id = ss.id
  AND l.teacher_id <> ss.teacher_id;
