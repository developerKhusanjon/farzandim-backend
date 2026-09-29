-- =============================================================================
-- Farzandim AI — dev seed #2: super admin paneli uchun namunaviy maktablar
--
-- Faqat `%dev` profilida (db/seed). Bosh sahifa, taqqoslash, ogohlantirishlar
-- va to'lovlar bo'limi bo'sh ko'rinmasligi uchun turli holatdagi maktablar:
--
--   nurli       PREMIUM   to'liq ishlayapti, AI faol ishlatiladi
--   ilmziyo     STANDARD  ishlayapti, obuna 14+ kundan beri to'lanmagan
--   kelajak     BASIC     30 kun oldin ulangan, lekin ishga tushmagan
--   yangiavlod  BASIC     obuna 5 kun oldin tugagan
--   oltinqalam  STANDARD  to'xtatilgan (to'lov kechikkan)
--
-- Barcha hisoblar paroli: parol123
-- =============================================================================

DO $$
DECLARE
    pw constant text := '$2a$10$Te/9W3oBXjpfm/q5lv4/dORbjIoVC8DGySvpCZciBo4bw2mp02KOS';

    s record;
    v_school uuid;
    v_user uuid;
    v_teacher uuid;
    v_teachers uuid[];
    v_subjects uuid[];
    v_groups uuid[];
    v_group uuid;
    v_student uuid;
    v_students uuid[];
    v_parent_user uuid;
    v_parent uuid;
    v_lesson uuid;
    v_subject uuid;
    d date;
    i int;
    j int;
    k int;
    n int;

    first_names text[] := ARRAY['Aziz','Dilnoza','Sardor','Madina','Bekzod','Zarina','Otabek','Kamola',
                                'Jasur','Sevara','Ulug''bek','Nigora','Temur','Lola','Shoxrux','Munisa',
                                'Asadbek','Gulnoza','Diyor','Robiya','Sanjar','Mohira','Islom','Durdona'];
    last_names  text[] := ARRAY['Karimov','Rahimova','Tursunov','Aliyeva','Yusupov','Saidova','Qodirov','Nazarova',
                                'Ergashev','Olimova','Hamidov','Sultonova','Rasulov','Umarova','Xolmatov','Sobirova',
                                'Mirzayev','Tosheva','Nematov','Jalilova','Haydarov','Zokirova','Abdullayev','Ahmedova'];
    subj text[] := ARRAY['Matematika','Ona tili','Ingliz tili','Informatika'];
BEGIN
    -- Asosiy seed bo'lmasa (masalan, bo'sh test bazasi) — hech narsa qilmaymiz.
    IF NOT EXISTS (SELECT 1 FROM schools WHERE short_name = 'zamonaviy') THEN
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM schools WHERE short_name = 'nurli') THEN
        RETURN;
    END IF;

    -- V900 dagi admin — V9 migratsiyasidan keyin yaratilgan, darajasi yo'q.
    UPDATE users SET admin_level = 'SUPER' WHERE role = 'SCHOOL_ADMIN' AND admin_level IS NULL;
    UPDATE users SET last_login_at = now() - interval '2 hours'
    WHERE username IN ('admin.maktab', 'oqituvchi1', 'oqituvchi2', 'otaona1', 'otaona2', 'otaona3') AND last_login_at IS NULL;

    UPDATE schools SET
        legal_name = '"Zamonaviy ta''lim" MChJ', tin = '305112233', director_name = 'Ismoilova Nodira',
        region = 'Toshkent shahri', city = 'Toshkent', contact_person = 'Ismoilova Nodira',
        contact_phone = '+998901110011', contract_number = 'FZ-2025/001', contract_signed_on = CURRENT_DATE - 60,
        created_at = now() - interval '2 months'
    WHERE short_name = 'zamonaviy';

    -- V900 obunasi V9 dan keyin yozilgan (standart UNPAID) — namunaviy maktab to'lagan.
    UPDATE school_subscriptions
    SET payment_status = 'PAID', paid_on = starts_on + 3, payment_method = 'bank', note = 'Shartnoma № FZ-2025/001'
    WHERE school_id = (SELECT id FROM schools WHERE short_name = 'zamonaviy');

    FOR s IN
        SELECT * FROM (VALUES
            (1, 'Nurli kelajak maktabi', 'nurli', '"Nurli kelajak" XK', '302456789', 'Toshkent shahri', 'Toshkent',
             'PREMIUM', 150, 'ACTIVE', NULL::text, 240, 14, 4, 3, 'PAID', 1000, 3750000),
            (2, 'Ilm ziyo xususiy maktabi', 'ilmziyo', '"Ilm ziyo" MChJ', '306778899', 'Samarqand viloyati', 'Samarqand',
             'STANDARD', 100, 'ACTIVE', NULL::text, 45, 12, 3, 2, 'UNPAID', 200, 1800000),
            (3, 'Kelajak akademiyasi', 'kelajak', '"Kelajak akademiyasi" MChJ', '307001122', 'Andijon viloyati', 'Andijon',
             'BASIC', 30, 'ACTIVE', NULL::text, 12, 0, 0, 0, 'UNPAID', 0, 600000),
            (4, 'Yangi avlod maktabi', 'yangiavlod', '"Yangi avlod" XK', '308334455', 'Farg''ona viloyati', 'Farg''ona',
             'BASIC', 200, 'ACTIVE', NULL::text, -5, 8, 2, 1, 'PAID', 0, 600000),
            (5, 'Oltin qalam maktabi', 'oltinqalam', '"Oltin qalam" MChJ', '309556677', 'Buxoro viloyati', 'Buxoro',
             'STANDARD', 120, 'SUSPENDED', 'To''lov 2 oydan beri kechiktirilgan', 60, 10, 2, 2, 'UNPAID', 200, 1800000)
        ) AS t(idx, name, short_name, legal_name, tin, region, city, plan, age_days, status, reason,
               until_days, students, teachers, groups_count, payment, ai_limit, monthly)
    LOOP
        INSERT INTO schools (name, short_name, legal_name, tin, region, city, address, phone, email,
                             director_name, contact_person, contact_phone, contract_number, contract_signed_on,
                             academic_year, grading_system, locale, subscription_plan, subscription_until,
                             status, status_reason, ai_daily_limit, created_at, settings)
        VALUES (s.name, s.short_name, s.legal_name, s.tin, s.region, s.city, s.city || ' sh., Mustaqillik ko''chasi ' || length(s.name),
                '+998 71 20' || s.idx || ' 00 00', 'info@' || s.short_name || '.uz',
                'Direktor ' || s.name, 'Mas''ul xodim', '+99890700000' || s.idx,
                'FZ-2025/00' || (s.idx + 1), CURRENT_DATE - s.age_days,
                '2025-2026', 'HUNDRED', 'uz', s.plan, CURRENT_DATE + s.until_days,
                s.status, s.reason, s.ai_limit, now() - make_interval(days => s.age_days),
                jsonb_build_object('notifyParentsOnGrade', true, 'notifyParentsOnHomework', true,
                                   'notifyParentsOnAbsence', true, 'aiAssistantEnabled', s.plan <> 'BASIC',
                                   'autoLessonSummary', false, 'weeklyReportDay', 6,
                                   'lessonDurationMinutes', 45, 'breakMinutes', 10, 'dayStart', '08:30'))
        RETURNING id INTO v_school;

        -- Joriy obuna: shartnoma kunidan muddat oxirigacha
        INSERT INTO school_subscriptions (school_id, plan_code, starts_on, ends_on, student_count, amount_uzs,
                                          status, payment_status, paid_on, payment_method, note, created_at)
        VALUES (v_school, s.plan, CURRENT_DATE - s.age_days, CURRENT_DATE + s.until_days,
                s.students, s.monthly * GREATEST(1, round((s.age_days + s.until_days) / 30.0)),
                'ACTIVE', s.payment,
                CASE WHEN s.payment = 'PAID' THEN CURRENT_DATE - s.age_days + 5 END,
                CASE WHEN s.payment = 'PAID' THEN 'bank' END,
                'Shartnoma № FZ-2025/00' || (s.idx + 1),
                now() - make_interval(days => s.age_days));

        -- Adminlar: superadmin + (katta maktablarda) oddiy admin
        INSERT INTO users (school_id, role, admin_level, first_name, last_name, username, phone, password_hash,
                           status, locale, last_login_at, must_change_password)
        VALUES (v_school, 'SCHOOL_ADMIN', 'SUPER', 'Direktor', initcap(s.short_name), s.short_name || '.admin',
                '+99891700000' || s.idx, pw,
                CASE WHEN s.short_name = 'kelajak' THEN 'INVITED' ELSE 'ACTIVE' END, 'uz',
                CASE WHEN s.short_name IN ('kelajak', 'yangiavlod') THEN NULL ELSE now() - interval '1 day' END,
                s.short_name = 'kelajak');
        IF s.students >= 10 THEN
            INSERT INTO users (school_id, role, admin_level, first_name, last_name, username, password_hash,
                               status, locale, last_login_at)
            VALUES (v_school, 'SCHOOL_ADMIN', 'REGULAR', 'Zavuch', initcap(s.short_name), s.short_name || '.zavuch',
                    pw, 'ACTIVE', 'uz', now() - interval '3 days');
        END IF;

        CONTINUE WHEN s.students = 0;

        -- Fanlar
        v_subjects := '{}';
        FOR i IN 1..array_length(subj, 1) LOOP
            INSERT INTO subjects (school_id, name, short_name, color_key, icon_key, hours_per_week, grade_levels)
            VALUES (v_school, subj[i], left(subj[i], 3), (ARRAY['blue','green','amber','purple'])[i], 'book', 4, ARRAY[5, 6])
            RETURNING id INTO v_subject;
            v_subjects := array_append(v_subjects, v_subject);
        END LOOP;

        -- O'qituvchilar
        v_teachers := '{}';
        FOR i IN 1..s.teachers LOOP
            INSERT INTO users (school_id, role, first_name, last_name, username, password_hash, status, locale, last_login_at)
            VALUES (v_school, 'TEACHER', first_names[i + 4], last_names[i + 4], s.short_name || '.oqituvchi' || i,
                    pw, 'ACTIVE', 'uz', now() - make_interval(hours => i * 5))
            RETURNING id INTO v_user;
            INSERT INTO teachers (school_id, user_id, qualification, experience_years, hired_at)
            VALUES (v_school, v_user, 'Birinchi toifa', 3 + i, CURRENT_DATE - 400)
            RETURNING id INTO v_teacher;
            v_teachers := array_append(v_teachers, v_teacher);
        END LOOP;

        -- Guruhlar va biriktirishlar
        v_groups := '{}';
        FOR i IN 1..s.groups_count LOOP
            INSERT INTO groups (school_id, name, grade_level, academic_year, class_teacher_id, room)
            VALUES (v_school, (4 + i) || 'A', 4 + i, '2025-2026', v_teachers[1 + (i - 1) % s.teachers], '10' || i)
            RETURNING id INTO v_group;
            v_groups := array_append(v_groups, v_group);
            FOR j IN 1..array_length(v_subjects, 1) LOOP
                INSERT INTO teacher_assignments (school_id, teacher_id, group_id, subject_id)
                VALUES (v_school, v_teachers[1 + (j - 1) % s.teachers], v_group, v_subjects[j])
                ON CONFLICT DO NOTHING;
            END LOOP;
            FOR j IN 1..5 LOOP
                INSERT INTO schedule_slots (school_id, group_id, subject_id, teacher_id, day_of_week, start_time, end_time, room)
                VALUES (v_school, v_group, v_subjects[1 + (j - 1) % 4], v_teachers[1 + (j - 1) % s.teachers], j,
                        TIME '08:30', TIME '09:15', '10' || i);
            END LOOP;
        END LOOP;

        -- O'quvchilar va ota-onalar (har uchinchi ota-ona ilovaga kirmagan)
        v_students := '{}';
        FOR i IN 1..s.students LOOP
            v_group := v_groups[1 + (i - 1) % s.groups_count];
            INSERT INTO students (school_id, first_name, last_name, birth_date, gender, grade_level, group_id,
                                  enrolled_at, status, created_at)
            VALUES (v_school, first_names[1 + (i * 7) % 24], last_names[1 + (i * 5) % 24],
                    DATE '2014-03-01' + i * 17, CASE WHEN i % 2 = 0 THEN 'M' ELSE 'F' END,
                    5 + (i - 1) % s.groups_count, v_group, CURRENT_DATE - s.age_days + 3, 'ACTIVE',
                    now() - make_interval(days => GREATEST(1, s.age_days - 3 - i)))
            RETURNING id INTO v_student;
            v_students := array_append(v_students, v_student);

            INSERT INTO users (school_id, role, first_name, last_name, username, password_hash, status, locale, last_login_at)
            VALUES (v_school, 'PARENT', CASE WHEN i % 2 = 0 THEN 'Malika' ELSE 'Rustam' END, last_names[1 + (i * 5) % 24],
                    s.short_name || '.otaona' || i, pw, 'ACTIVE', 'uz',
                    CASE WHEN i % 3 = 0 THEN NULL ELSE now() - make_interval(hours => i * 3) END)
            RETURNING id INTO v_parent_user;
            INSERT INTO parents (school_id, user_id, preferred_contact) VALUES (v_school, v_parent_user, 'APP')
            RETURNING id INTO v_parent;
            INSERT INTO student_parents (student_id, parent_id, relation, is_primary)
            VALUES (v_student, v_parent, CASE WHEN i % 2 = 0 THEN 'MOTHER' ELSE 'FATHER' END, true);
        END LOOP;

        -- Oxirgi 30 kundagi darslar, davomat va baholar (to'xtatilgan maktabda faollik to'xtagan)
        FOR d IN SELECT g::date FROM generate_series(CURRENT_DATE - 30,
                     CURRENT_DATE - CASE WHEN s.status = 'SUSPENDED' THEN 20 ELSE 1 END, interval '1 day') g LOOP
            CONTINUE WHEN extract(isodow FROM d) > 5;
            FOR i IN 1..s.groups_count LOOP
                FOR k IN 1..2 LOOP
                    v_subject := v_subjects[1 + (extract(day FROM d)::int + k) % 4];
                    INSERT INTO lessons (school_id, group_id, subject_id, teacher_id, lesson_date, start_time, end_time,
                                         title, status, summary_status, summary_sent_at)
                    VALUES (v_school, v_groups[i], v_subject, v_teachers[1 + (i + k) % s.teachers], d,
                            TIME '08:30' + (k - 1) * INTERVAL '55 minutes', TIME '09:15' + (k - 1) * INTERVAL '55 minutes',
                            'Dars ' || to_char(d, 'DD.MM'), 'DONE',
                            CASE WHEN s.plan = 'BASIC' OR k = 2 THEN 'NONE' ELSE 'SENT_TO_PARENTS' END,
                            CASE WHEN s.plan = 'BASIC' OR k = 2 THEN NULL ELSE d + TIME '15:00' END)
                    RETURNING id INTO v_lesson;

                    n := 0;
                    FOR j IN 1..array_length(v_students, 1) LOOP
                        CONTINUE WHEN (j - 1) % s.groups_count <> i - 1;
                        n := n + 1;
                        INSERT INTO attendance (school_id, student_id, lesson_id, attended_on, status)
                        VALUES (v_school, v_students[j], v_lesson, d,
                                CASE WHEN (j + extract(day FROM d)::int) % 13 = 0 THEN 'ABSENT'
                                     WHEN (j + extract(day FROM d)::int) % 17 = 0 THEN 'LATE'
                                     ELSE 'PRESENT' END);
                        IF (n + k) % 3 = 0 THEN
                            INSERT INTO grades (school_id, student_id, lesson_id, subject_id, teacher_id, value, max_value, type, graded_on)
                            VALUES (v_school, v_students[j], v_lesson, v_subject, v_teachers[1 + (i + k) % s.teachers],
                                    55 + ((j * 7 + extract(day FROM d)::int * 3 + length(s.short_name) * 5) % 45), 100,
                                    CASE WHEN (j + k) % 9 = 0 THEN 'QUIZ' ELSE 'CLASSWORK' END, d);
                        END IF;
                    END LOOP;
                END LOOP;

                IF extract(isodow FROM d) IN (2, 4) THEN
                    INSERT INTO homework (school_id, group_id, subject_id, teacher_id, title, description, due_date,
                                          ai_generated, sent_to_parents_at, created_at)
                    VALUES (v_school, v_groups[i], v_subjects[1], v_teachers[1], 'Uy vazifasi ' || to_char(d, 'DD.MM'),
                            'Darslikdagi mashqlar', d + 2, s.plan <> 'BASIC', d + TIME '16:00', d + TIME '15:30');
                END IF;
            END LOOP;
        END LOOP;

        -- AI sarfi (faqat AI bor tariflarda)
        IF s.plan <> 'BASIC' THEN
            FOR d IN SELECT g::date FROM generate_series(CURRENT_DATE - 75,
                         CURRENT_DATE - CASE WHEN s.status = 'SUSPENDED' THEN 20 ELSE 0 END, interval '1 day') g LOOP
                CONTINUE WHEN extract(isodow FROM d) > 6;
                INSERT INTO ai_usage_daily (school_id, usage_date, feature, calls, input_tokens, output_tokens, cost_micros)
                VALUES
                    (v_school, d, 'LESSON_SUMMARY', 3 + (extract(day FROM d)::int % 5) * (CASE s.plan WHEN 'PREMIUM' THEN 3 ELSE 1 END),
                     12000, 4000, 1800000000),
                    (v_school, d, 'HOMEWORK_GENERATE', 2 + (extract(day FROM d)::int % 4), 6000, 3000, 950000000),
                    (v_school, d, 'CHAT', 5 + (extract(day FROM d)::int % 7) * (CASE s.plan WHEN 'PREMIUM' THEN 4 ELSE 2 END),
                     20000, 8000, 2600000000);
            END LOOP;
        END IF;
    END LOOP;

    -- Asosiy seed maktabining AI tarixi (trend grafigi uchun)
    INSERT INTO ai_usage_daily (school_id, usage_date, feature, calls, input_tokens, output_tokens, cost_micros)
    SELECT id, g::date, 'LESSON_SUMMARY', 6 + extract(day FROM g)::int % 6, 15000, 5000, 2200000000
    FROM schools, generate_series(CURRENT_DATE - 60, CURRENT_DATE - 1, interval '1 day') g
    WHERE short_name = 'zamonaviy'
    ON CONFLICT DO NOTHING;

    -- Monitoring uchun bir nechta yiqilgan AI job
    INSERT INTO ai_jobs (school_id, type, status, attempts, error_code, error_message, model, created_at, updated_at, finished_at)
    SELECT id, 'LESSON_SUMMARY', 'FAILED', 3, 'AI_UNAVAILABLE', 'AI xizmati hozir javob bermayapti',
           'claude-sonnet-5', now() - interval '5 hours', now() - interval '4 hours', now() - interval '4 hours'
    FROM schools WHERE short_name IN ('nurli', 'ilmziyo');

    -- Ichki qaydlar
    INSERT INTO school_notes (school_id, kind, body, created_at)
    SELECT id, 'CONTRACT', 'Shartnoma imzolandi, birinchi to''lov bank orqali.', created_at + interval '1 day'
    FROM schools WHERE short_name IN ('nurli', 'ilmziyo', 'zamonaviy');
    INSERT INTO school_notes (school_id, kind, body)
    SELECT id, 'CALL', 'Direktor bilan gaplashildi: o''quvchilar ro''yxatini hafta oxirigacha yuboradi.'
    FROM schools WHERE short_name = 'kelajak';
END $$;
