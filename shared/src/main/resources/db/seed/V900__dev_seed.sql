-- =============================================================================
-- Farzandim AI — DEV uchun namunaviy ma'lumot
--
-- Faqat `%dev` profilida yuklanadi (application.properties dagi
-- `quarkus.flyway.locations=db/migration,db/seed`). Produksiyada bu fayl
-- Flyway lokatsiyalariga kirmaydi.
--
-- Kirish (parol hammasi uchun bir xil):
--   super admin   : superadmin / admin123      (admin-backend, 8083)
--   maktab admini : admin.maktab / parol123    (school-backend, 8081)
--   o'qituvchi    : oqituvchi1 / parol123      (school-backend, 8081)
--   ota-ona       : otaona1 / parol123         (mobile-backend, 8082)
-- =============================================================================

DO $$
DECLARE
    pw_parol123 constant text := '$2a$10$Te/9W3oBXjpfm/q5lv4/dORbjIoVC8DGySvpCZciBo4bw2mp02KOS';
    pw_admin123 constant text := '$2a$10$UFVZYo9K8u/.T20/WDg/Yum3EGC1o5prpoS50zcqBgqV2uyVOaNOG';

    v_school_id uuid;
    v_subject_ids uuid[] := '{}';
    v_subject_id uuid;
    v_teacher_ids uuid[] := '{}';
    v_teacher_id uuid;
    v_group_ids uuid[] := '{}';
    v_group_id uuid;
    v_student_id uuid;
    v_parent_user_id uuid;
    v_parent_id uuid;
    v_user_id uuid;
    v_slot_id uuid;
    v_lesson_id uuid;
    v_topic_id uuid;
    v_homework_id uuid;

    subject_names text[] := ARRAY['Matematika','Ona tili','Ingliz tili','Tabiatshunoslik','Informatika','Jismoniy tarbiya'];
    subject_short text[] := ARRAY['Mat','Ona','Ing','Tab','Inf','Jis'];
    subject_color text[] := ARRAY['blue','green','amber','teal','purple','pink'];

    teacher_first text[] := ARRAY['Dilnoza','Sardor','Nigora','Jasur'];
    teacher_last  text[] := ARRAY['Karimova','Aliyev','Yusupova','Rahimov'];

    student_first text[] := ARRAY['Aziza','Bekzod','Madina','Jasurbek','Nilufar','Otabek','Zilola','Sherzod',
                                  'Gulnora','Islom','Kamola','Doston','Malika','Temur','Sevara','Alisher',
                                  'Dilshoda','Bobur','Nodira','Ulugbek','Zarina','Farrux','Shahnoza','Rustam',
                                  'Mohira','Javohir','Lola','Akmal','Feruza','Sanjar'];
    student_last  text[] := ARRAY['Tosheva','Nazarov','Qodirova','Ergashev','Xolmatova','Saidov','Umarova','Mirzayev',
                                  'Yo''ldosheva','Abdullayev','Ismoilova','Tursunov','Rasulova','Bekmurodov','Sultonova','Hamidov',
                                  'Norboyeva','Qurbonov','Shodiyeva','Mamatov','Olimova','Haydarov','Zokirova','Jalilov',
                                  'Ahmedova','Nematov','Sobirova','Xudoyorov','Kamolova','Toshpulatov'];

    i int;
    j int;
    k int;
    d date;
    grade_value numeric;
BEGIN
    IF EXISTS (SELECT 1 FROM schools) THEN
        RAISE NOTICE 'Seed o''tkazib yuborildi: baza allaqachon to''ldirilgan';
        RETURN;
    END IF;

    ----------------------------------------------------------------- super admin
    INSERT INTO users (school_id, role, first_name, last_name, username, email, password_hash, status, locale)
    VALUES (NULL, 'SUPER_ADMIN', 'Platforma', 'Egasi', 'superadmin', 'super@farzandim.uz', pw_admin123, 'ACTIVE', 'uz');

    ---------------------------------------------------------------------- maktab
    INSERT INTO schools (name, short_name, address, phone, email, academic_year, grading_system,
                         locale, subscription_plan, subscription_until, ai_daily_limit, settings)
    VALUES ('Zamonaviy ta''lim maktabi', 'zamonaviy', 'Toshkent sh., Yunusobod t., Amir Temur ko''chasi 12',
            '+998 71 200 10 10', 'info@zamonaviy.uz', '2025-2026', 'HUNDRED', 'uz', 'PREMIUM',
            CURRENT_DATE + 300, 1000,
            '{"notifyParentsOnGrade": true, "notifyParentsOnHomework": true, "notifyParentsOnAbsence": true,
              "aiAssistantEnabled": true, "autoLessonSummary": false, "weeklyReportDay": 6,
              "lessonDurationMinutes": 45, "breakMinutes": 10, "dayStart": "08:30"}'::jsonb)
    RETURNING id INTO v_school_id;

    INSERT INTO school_subscriptions (school_id, plan_code, starts_on, ends_on, student_count, amount_uzs, status)
    VALUES (v_school_id, 'PREMIUM', CURRENT_DATE - 60, CURRENT_DATE + 300, 30, 22500000, 'ACTIVE');

    --------------------------------------------------------------- maktab admini
    INSERT INTO users (school_id, role, first_name, last_name, username, email, phone, password_hash, status, locale)
    VALUES (v_school_id, 'SCHOOL_ADMIN', 'Nodira', 'Ismoilova', 'admin.maktab', 'admin@zamonaviy.uz',
            '+998901110011', pw_parol123, 'ACTIVE', 'uz');

    ----------------------------------------------------------------------- fanlar
    FOR i IN 1..array_length(subject_names, 1) LOOP
        INSERT INTO subjects (school_id, name, short_name, color_key, icon_key, hours_per_week, grade_levels)
        VALUES (v_school_id, subject_names[i], subject_short[i], subject_color[i], 'book',
                CASE WHEN i <= 3 THEN 5 ELSE 2 END, ARRAY[4, 5])
        RETURNING id INTO v_subject_id;
        v_subject_ids := array_append(v_subject_ids, v_subject_id);

        -- Har bir fanga 6 ta mavzu
        FOR j IN 1..6 LOOP
            INSERT INTO topics (school_id, subject_id, grade_level, order_index, title, description, expected_terms)
            VALUES (v_school_id, v_subject_id, 4, j,
                    subject_names[i] || ' — ' || j || '-mavzu',
                    'Dastur bo''yicha ' || j || '-mavzu tavsifi',
                    ARRAY['atama' || j, 'tushuncha' || j]);
        END LOOP;
    END LOOP;

    ------------------------------------------------------------------ o'qituvchilar
    FOR i IN 1..array_length(teacher_first, 1) LOOP
        INSERT INTO users (school_id, role, first_name, last_name, username, email, phone, password_hash, status, locale)
        VALUES (v_school_id, 'TEACHER', teacher_first[i], teacher_last[i], 'oqituvchi' || i,
                'oqituvchi' || i || '@zamonaviy.uz', '+99890222' || lpad(i::text, 4, '0'),
                pw_parol123, 'ACTIVE', 'uz')
        RETURNING id INTO v_user_id;

        INSERT INTO teachers (school_id, user_id, employee_no, qualification, experience_years, hired_at)
        VALUES (v_school_id, v_user_id, 'EMP-' || lpad(i::text, 3, '0'), 'Oliy toifa', 5 + i, CURRENT_DATE - 900)
        RETURNING id INTO v_teacher_id;

        v_teacher_ids := array_append(v_teacher_ids, v_teacher_id);
    END LOOP;

    ---------------------------------------------------------------------- guruhlar
    FOR i IN 1..3 LOOP
        INSERT INTO groups (school_id, name, grade_level, academic_year, class_teacher_id, room)
        VALUES (v_school_id,
                CASE i WHEN 1 THEN '4A' WHEN 2 THEN '4B' ELSE '5A' END,
                CASE i WHEN 3 THEN 5 ELSE 4 END,
                '2025-2026', v_teacher_ids[i], '20' || i)
        RETURNING id INTO v_group_id;
        v_group_ids := array_append(v_group_ids, v_group_id);

        -- Har bir guruhga barcha fanlar bo'yicha o'qituvchi biriktiramiz
        FOR j IN 1..array_length(v_subject_ids, 1) LOOP
            INSERT INTO teacher_assignments (school_id, teacher_id, group_id, subject_id)
            VALUES (v_school_id, v_teacher_ids[1 + (j % array_length(v_teacher_ids, 1))],
                    v_group_id, v_subject_ids[j]);
        END LOOP;
    END LOOP;

    ------------------------------------------------------------------ dars jadvali
    FOR i IN 1..array_length(v_group_ids, 1) LOOP
        FOR j IN 1..5 LOOP                       -- dushanba..juma
            FOR k IN 1..4 LOOP                   -- kuniga 4 ta dars
                INSERT INTO schedule_slots (school_id, group_id, subject_id, teacher_id, day_of_week,
                                            start_time, end_time, room)
                VALUES (v_school_id, v_group_ids[i],
                        v_subject_ids[1 + ((j + k) % array_length(v_subject_ids, 1))],
                        v_teacher_ids[1 + ((j + k) % array_length(v_teacher_ids, 1))],
                        j,
                        (TIME '08:30' + ((k - 1) * INTERVAL '55 minutes')),
                        (TIME '09:15' + ((k - 1) * INTERVAL '55 minutes')),
                        '20' || i);
            END LOOP;
        END LOOP;
    END LOOP;

    ------------------------------------------------------ o'quvchilar va ota-onalar
    FOR i IN 1..30 LOOP
        v_group_id := v_group_ids[1 + ((i - 1) % 3)];

        INSERT INTO students (school_id, first_name, last_name, birth_date, gender, grade_level,
                              group_id, enrolled_at, status)
        VALUES (v_school_id, student_first[i], student_last[i],
                DATE '2015-01-01' + (i * 11), CASE WHEN i % 2 = 0 THEN 'M' ELSE 'F' END,
                CASE WHEN (i - 1) % 3 = 2 THEN 5 ELSE 4 END,
                v_group_id, CURRENT_DATE - 400, 'ACTIVE')
        RETURNING id INTO v_student_id;

        INSERT INTO users (school_id, role, first_name, last_name, username, phone, password_hash, status, locale)
        VALUES (v_school_id, 'PARENT',
                CASE WHEN i % 2 = 0 THEN 'Malika' ELSE 'Rustam' END,
                student_last[i], 'otaona' || i, '+99893' || lpad(i::text, 7, '0'),
                pw_parol123, 'ACTIVE', 'uz')
        RETURNING id INTO v_parent_user_id;

        INSERT INTO parents (school_id, user_id, preferred_contact)
        VALUES (v_school_id, v_parent_user_id, 'APP')
        RETURNING id INTO v_parent_id;

        INSERT INTO student_parents (student_id, parent_id, relation, is_primary)
        VALUES (v_student_id, v_parent_id,
                CASE WHEN i % 2 = 0 THEN 'MOTHER' ELSE 'FATHER' END, true);

        -- Qiziqishlar
        INSERT INTO interests (school_id, student_id, key, label, description, level, color_key, icon_key, source)
        VALUES
            (v_school_id, v_student_id, 'robotics', 'Robototexnika', 'Konstruktor va mexanizmlarga qiziqadi',
             40 + (i * 7) % 60, 'blue', 'cpu', 'TEACHER'),
            (v_school_id, v_student_id, 'reading', 'Kitobxonlik', 'Badiiy kitoblarni sevib o''qiydi',
             30 + (i * 13) % 65, 'green', 'book', 'TEACHER');

        -- Ko'nikma profili
        INSERT INTO skill_assessments (school_id, student_id, teacher_id, assessed_on, category, skills, summary, source)
        VALUES (v_school_id, v_student_id, v_teacher_ids[1 + (i % 4)], CURRENT_DATE - 20, 'COGNITIVE',
                jsonb_build_array(
                    jsonb_build_object('key', 'logic', 'label', 'Mantiqiy fikrlash',
                                       'score', 45 + (i * 9) % 50, 'note', 'Masala yechishda izchil'),
                    jsonb_build_object('key', 'memory', 'label', 'Xotira',
                                       'score', 50 + (i * 5) % 45, 'note', 'She''r yodlashda kuchli'),
                    jsonb_build_object('key', 'attention', 'label', 'Diqqat',
                                       'score', 40 + (i * 11) % 55, 'note', 'Uzoq vazifada charchaydi')),
                'Pedagogik kuzatuv natijasi — psixologik diagnoz emas.', 'TEACHER_TASK');
    END LOOP;

    ------------------------------------------------------ darslar, baho va davomat
    -- Oxirgi 30 kun ichidagi ish kunlari uchun dars yozuvlari
    FOR d IN SELECT generate_series(CURRENT_DATE - 30, CURRENT_DATE, INTERVAL '1 day')::date LOOP
        CONTINUE WHEN EXTRACT(ISODOW FROM d) > 5;

        FOR v_slot_id IN
            SELECT id FROM schedule_slots
            WHERE school_id = v_school_id AND day_of_week = EXTRACT(ISODOW FROM d)::int
        LOOP
            INSERT INTO lessons (school_id, schedule_slot_id, group_id, subject_id, teacher_id, topic_id,
                                 lesson_date, start_time, end_time, room, title, status)
            SELECT v_school_id, s.id, s.group_id, s.subject_id, s.teacher_id,
                   (SELECT t.id FROM topics t WHERE t.subject_id = s.subject_id
                    ORDER BY t.order_index LIMIT 1 OFFSET (EXTRACT(DAY FROM d)::int % 6)),
                   d, s.start_time, s.end_time, s.room,
                   (SELECT name FROM subjects WHERE id = s.subject_id) || ' darsi',
                   CASE WHEN d < CURRENT_DATE THEN 'DONE' ELSE 'PLANNED' END
            FROM schedule_slots s WHERE s.id = v_slot_id
            RETURNING id, group_id, subject_id, teacher_id, topic_id
            INTO v_lesson_id, v_group_id, v_subject_id, v_teacher_id, v_topic_id;

            CONTINUE WHEN d >= CURRENT_DATE;

            -- Davomat va baholar (hammasiga emas — jurnal real ko'rinsin)
            FOR v_student_id IN
                SELECT id FROM students WHERE group_id = v_group_id AND status = 'ACTIVE'
            LOOP
                INSERT INTO attendance (school_id, student_id, lesson_id, attended_on, status)
                VALUES (v_school_id, v_student_id, v_lesson_id, d,
                        CASE
                            WHEN random() < 0.90 THEN 'PRESENT'
                            WHEN random() < 0.60 THEN 'LATE'
                            WHEN random() < 0.50 THEN 'EXCUSED'
                            ELSE 'ABSENT'
                        END);

                IF random() < 0.35 THEN
                    grade_value := 45 + floor(random() * 56);
                    INSERT INTO grades (school_id, student_id, lesson_id, subject_id, topic_id, teacher_id,
                                        value, max_value, type, graded_on, sent_to_parent_at)
                    VALUES (v_school_id, v_student_id, v_lesson_id, v_subject_id, v_topic_id, v_teacher_id,
                            grade_value, 100,
                            (ARRAY['CLASSWORK','HOMEWORK','QUIZ','ORAL'])[1 + floor(random() * 4)::int],
                            d, now());
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;

    -- Bitta darsga tayyor AI xulosasi (ota-onalarga yuborilgan holatda)
    UPDATE lessons SET
        summary = 'Bugun kasrlar mavzusi o''rganildi. Darsda o''quvchilar kasrni qismlarga bo''lish, '
                  || 'teng bo''laklarni solishtirish va kundalik hayotdan misollar ustida ishladi. '
                  || 'Guruh mavzuni umuman yaxshi o''zlashtirdi, ayrim o''quvchilarga qo''shimcha mashq foydali bo''ladi.',
        terms = '[{"term": "Kasr", "definition": "Butunning teng bo''laklaridan biri yoki bir nechtasi"},
                  {"term": "Surat", "definition": "Kasrning yuqoridagi soni — nechta bo''lak olinganini ko''rsatadi"},
                  {"term": "Maxraj", "definition": "Kasrning pastdagi soni — butun nechta bo''lakka bo''linganini ko''rsatadi"}]'::jsonb,
        summary_status = 'SENT_TO_PARENTS',
        summary_sent_at = now()
    WHERE id IN (
        SELECT id FROM lessons WHERE school_id = v_school_id AND lesson_date < CURRENT_DATE
        ORDER BY lesson_date DESC LIMIT 3
    );

    ------------------------------------------------------------------ uy vazifalari
    FOR i IN 1..array_length(v_group_ids, 1) LOOP
        FOR j IN 1..3 LOOP
            SELECT ta.subject_id, ta.teacher_id INTO v_subject_id, v_teacher_id
            FROM teacher_assignments ta WHERE ta.group_id = v_group_ids[i] LIMIT 1 OFFSET (j - 1);

            INSERT INTO homework (school_id, group_id, subject_id, teacher_id, title, description,
                                  due_date, estimated_minutes, scope, difficulty, ai_generated,
                                  parent_hint, sent_to_parents_at)
            VALUES (v_school_id, v_group_ids[i], v_subject_id, v_teacher_id,
                    'Kasrlar bo''yicha mashqlar ' || j,
                    E'1. 1/2, 1/4 va 3/4 kasrlarni chizmada ko''rsating.\n'
                    || E'2. Oshxonadagi masala: 1 kg unning 3/4 qismi necha gramm?\n'
                    || E'3. Quyidagi kasrlarni kattadan kichikka tartiblang: 2/3, 1/2, 5/6.\n'
                    || E'4. Do''kon masalasi: 12 ta olmaning 1/3 qismini oldingiz. Nechta olma oldingiz?',
                    CURRENT_DATE + j, 25, 'GROUP', 'NORMAL', false,
                    'Bola qiynalsa, javobni aytmang — "Butun nechta bo''lakka bo''lingan?" deb so''rang. '
                    || 'Kasrni tortga yoki nonga bo''lib ko''rsatish eng oson usul.',
                    now())
            RETURNING id INTO v_homework_id;

            -- Topshiriq yozuvlari
            INSERT INTO homework_submissions (school_id, homework_id, student_id, status, completed_at, parent_confirmed)
            SELECT v_school_id, v_homework_id, s.id,
                   CASE WHEN random() < 0.6 THEN 'DONE' ELSE 'PENDING' END,
                   CASE WHEN random() < 0.6 THEN now() ELSE NULL END,
                   random() < 0.5
            FROM students s WHERE s.group_id = v_group_ids[i] AND s.status = 'ACTIVE';
        END LOOP;
    END LOOP;

    ----------------------------------------------------------- kognitiv tavsiyalar
    INSERT INTO cognitive_recommendations (school_id, student_id, teacher_id, area, title, description,
                                           rationale, steps, duration_minutes, difficulty, status, assigned_to, created_by_ai)
    SELECT v_school_id, s.id, v_teacher_ids[1], 'LOGIC',
           'Kuniga 10 daqiqa mantiqiy masala',
           'Har kuni kechqurun bitta mantiqiy masala yeching. Javobni aytmang — savol bering.',
           'Matematikada mavzu bo''yicha natija 62% — kasrlar mavzusida qiynalmoqda.',
           ARRAY['Masalani birga o''qing', 'Bola o''z fikrini aytsin', 'Chizma chizishni taklif qiling',
                 'Javobni tekshiring va maqtang'],
           10, 'NORMAL', 'SUGGESTED', 'PARENT', true
    FROM students s WHERE s.school_id = v_school_id LIMIT 10;

    RAISE NOTICE 'Seed tayyor: maktab=%, o''quvchi=30, o''qituvchi=4, guruh=3', v_school_id;
END $$;
