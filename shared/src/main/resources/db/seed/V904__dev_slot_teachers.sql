-- Dev seed: jadvaldagi o'qituvchi guruh+fanga biriktirilgan o'qituvchi bilan bir xil bo'lsin
-- (V900 ularni turli formula bilan tanlaydi)
UPDATE schedule_slots ss
SET teacher_id = ta.teacher_id
FROM teacher_assignments ta
WHERE ta.school_id = ss.school_id
  AND ta.group_id = ss.group_id
  AND ta.subject_id = ss.subject_id
  AND ta.teacher_id <> ss.teacher_id;

-- Darslar ham slotdagi o'qituvchiga
UPDATE lessons l
SET teacher_id = ss.teacher_id
FROM schedule_slots ss
WHERE l.schedule_slot_id = ss.id
  AND l.teacher_id <> ss.teacher_id;
