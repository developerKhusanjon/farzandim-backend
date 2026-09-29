-- =============================================================================
-- Farzandim AI — V2: o'quv tuzilmasi (fan, mavzu, guruh, odamlar, jadval, dars)
-- =============================================================================

CREATE TABLE subjects (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    name          text        NOT NULL,
    short_name    text        NOT NULL,
    color_key     text        NOT NULL DEFAULT 'blue',
    icon_key      text        NOT NULL DEFAULT 'book',
    hours_per_week integer    NOT NULL DEFAULT 2,
    grade_levels  integer[]   NOT NULL DEFAULT '{}',
    archived      boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX subjects_school_idx ON subjects (school_id) WHERE NOT archived;

CREATE TABLE topics (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    subject_id  uuid        NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    grade_level integer     NOT NULL CHECK (grade_level BETWEEN 1 AND 11),
    order_index integer     NOT NULL DEFAULT 0,
    title       text        NOT NULL,
    description text,
    expected_terms text[]   NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX topics_subject_idx ON topics (subject_id, grade_level, order_index);

CREATE TABLE teachers (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    employee_no     text,
    qualification   text,
    experience_years integer    NOT NULL DEFAULT 0,
    hired_at        date,
    bio             text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX teachers_user_key ON teachers (user_id);
CREATE INDEX teachers_school_idx      ON teachers (school_id);

CREATE TABLE groups (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id        uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    name             text        NOT NULL,
    grade_level      integer     NOT NULL CHECK (grade_level BETWEEN 1 AND 11),
    academic_year    text        NOT NULL,
    class_teacher_id uuid REFERENCES teachers (id) ON DELETE SET NULL,
    room             text,
    archived         boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX groups_name_key  ON groups (school_id, academic_year, lower(name));
CREATE INDEX groups_school_idx       ON groups (school_id) WHERE NOT archived;

CREATE TABLE students (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    user_id     uuid REFERENCES users (id) ON DELETE SET NULL,   -- ixtiyoriy kirish
    first_name  text        NOT NULL,
    last_name   text        NOT NULL,
    middle_name text,
    avatar_url  text,
    birth_date  date,
    gender      text CHECK (gender IN ('M', 'F')),
    grade_level integer     NOT NULL CHECK (grade_level BETWEEN 1 AND 11),
    group_id    uuid REFERENCES groups (id) ON DELETE SET NULL,
    enrolled_at date        NOT NULL DEFAULT CURRENT_DATE,
    status      text        NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'TRANSFERRED', 'GRADUATED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX students_school_idx ON students (school_id, status);
CREATE INDEX students_group_idx  ON students (group_id) WHERE status = 'ACTIVE';

CREATE TABLE parents (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id         uuid REFERENCES schools (id) ON DELETE CASCADE,  -- NULL → mustaqil (B2C)
    user_id           uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    workplace         text,
    preferred_contact text        NOT NULL DEFAULT 'PHONE'
                                  CHECK (preferred_contact IN ('PHONE', 'EMAIL', 'APP')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX parents_user_key ON parents (user_id);
CREATE INDEX parents_school_idx      ON parents (school_id);

CREATE TABLE student_parents (
    student_id uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    parent_id  uuid        NOT NULL REFERENCES parents (id) ON DELETE CASCADE,
    relation   text        NOT NULL CHECK (relation IN ('MOTHER', 'FATHER', 'GUARDIAN')),
    is_primary boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, parent_id)
);

CREATE INDEX student_parents_parent_idx ON student_parents (parent_id);

-- O'qituvchi × guruh × fan
CREATE TABLE teacher_assignments (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id  uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    teacher_id uuid        NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    group_id   uuid        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    subject_id uuid        NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX teacher_assignments_key ON teacher_assignments (teacher_id, group_id, subject_id);
CREATE INDEX teacher_assignments_group_idx  ON teacher_assignments (group_id, subject_id);
CREATE INDEX teacher_assignments_school_idx ON teacher_assignments (school_id);

CREATE TABLE schedule_slots (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    group_id    uuid        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    subject_id  uuid        NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    teacher_id  uuid        NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    day_of_week integer     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time  time        NOT NULL,
    end_time    time        NOT NULL,
    room        text,
    week_type   text        NOT NULL DEFAULT 'ALL' CHECK (week_type IN ('ALL', 'ODD', 'EVEN')),
    active_from date        NOT NULL DEFAULT CURRENT_DATE,
    active_to   date,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (end_time > start_time)
);

CREATE INDEX schedule_slots_group_idx   ON schedule_slots (group_id, day_of_week, start_time);
CREATE INDEX schedule_slots_teacher_idx ON schedule_slots (teacher_id, day_of_week, start_time);
CREATE INDEX schedule_slots_school_idx  ON schedule_slots (school_id);

CREATE TABLE lessons (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id        uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    schedule_slot_id uuid REFERENCES schedule_slots (id) ON DELETE SET NULL,
    group_id         uuid        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    subject_id       uuid        NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    teacher_id       uuid        NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    topic_id         uuid REFERENCES topics (id) ON DELETE SET NULL,
    lesson_date      date        NOT NULL,
    start_time       time        NOT NULL,
    end_time         time        NOT NULL,
    room             text,
    title            text        NOT NULL DEFAULT '',
    status           text        NOT NULL DEFAULT 'PLANNED'
                                 CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    teacher_notes    text,
    summary          text,
    terms            jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- [{term, definition}]
    summary_status   text        NOT NULL DEFAULT 'NONE'
                                 CHECK (summary_status IN ('NONE', 'AI_DRAFT', 'APPROVED', 'SENT_TO_PARENTS')),
    summary_sent_at  timestamptz,
    materials        jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- [{fileId, name, url}]
    started_at       timestamptz,
    finished_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX lessons_slot_date_key ON lessons (schedule_slot_id, lesson_date)
    WHERE schedule_slot_id IS NOT NULL;
CREATE INDEX lessons_group_date_idx   ON lessons (group_id, lesson_date DESC);
CREATE INDEX lessons_teacher_date_idx ON lessons (teacher_id, lesson_date DESC);
CREATE INDEX lessons_school_date_idx  ON lessons (school_id, lesson_date DESC);
