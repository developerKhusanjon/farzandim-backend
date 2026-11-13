-- Dev seed: fan ikonkalari (lucide nomlari, web'dagi SubjectIcon bilan bir xil)
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
