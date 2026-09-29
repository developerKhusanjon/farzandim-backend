-- =============================================================================
-- Farzandim AI — V3: baho, davomat, uy vazifasi, ko'nikma, tavsiya
-- =============================================================================

CREATE TABLE grades (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id      uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    student_id     uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    lesson_id      uuid REFERENCES lessons (id) ON DELETE SET NULL,
    subject_id     uuid        NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    topic_id       uuid REFERENCES topics (id) ON DELETE SET NULL,
    teacher_id     uuid        NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    value          numeric(5, 2) NOT NULL CHECK (value >= 0),
    max_value      numeric(5, 2) NOT NULL DEFAULT 100 CHECK (max_value > 0),
    type           text        NOT NULL
                               CHECK (type IN ('CLASSWORK', 'HOMEWORK', 'QUIZ', 'EXAM', 'PROJECT', 'ORAL')),
    comment        text,
    graded_on      date        NOT NULL DEFAULT CURRENT_DATE,
    sent_to_parent_at timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (value <= max_value)
);

CREATE INDEX grades_student_idx  ON grades (student_id, graded_on DESC);
CREATE INDEX grades_subject_idx  ON grades (subject_id, graded_on DESC);
CREATE INDEX grades_lesson_idx   ON grades (lesson_id);
CREATE INDEX grades_school_idx   ON grades (school_id, graded_on DESC);
CREATE INDEX grades_topic_idx    ON grades (topic_id) WHERE topic_id IS NOT NULL;

CREATE TABLE attendance (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id    uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    student_id   uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    lesson_id    uuid        NOT NULL REFERENCES lessons (id) ON DELETE CASCADE,
    attended_on  date        NOT NULL,
    status       text        NOT NULL CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'EXCUSED')),
    minutes_late integer,
    reason       text,
    marked_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX attendance_student_lesson_key ON attendance (student_id, lesson_id);
CREATE INDEX attendance_student_date_idx          ON attendance (student_id, attended_on DESC);
CREATE INDEX attendance_lesson_idx                ON attendance (lesson_id);
CREATE INDEX attendance_school_date_idx           ON attendance (school_id, attended_on DESC);

CREATE TABLE homework (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id         uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    group_id          uuid        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    subject_id        uuid        NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    teacher_id        uuid        NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    lesson_id         uuid REFERENCES lessons (id) ON DELETE SET NULL,
    topic_id          uuid REFERENCES topics (id) ON DELETE SET NULL,
    title             text        NOT NULL,
    description       text        NOT NULL,
    due_date          date        NOT NULL,
    estimated_minutes integer     NOT NULL DEFAULT 20,
    scope             text        NOT NULL DEFAULT 'GROUP' CHECK (scope IN ('GROUP', 'INDIVIDUAL')),
    target_student_ids uuid[]     NOT NULL DEFAULT '{}',
    difficulty        text        NOT NULL DEFAULT 'NORMAL' CHECK (difficulty IN ('EASY', 'NORMAL', 'HARD')),
    ai_generated      boolean     NOT NULL DEFAULT false,
    parent_hint       text,
    attachments       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    sent_to_parents_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX homework_group_due_idx  ON homework (group_id, due_date DESC);
CREATE INDEX homework_school_due_idx ON homework (school_id, due_date DESC);
CREATE INDEX homework_teacher_idx    ON homework (teacher_id, created_at DESC);

-- Qiynalayotgan o'quvchi uchun soddalashtirilgan variant
CREATE TABLE homework_variants (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id              uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    homework_id            uuid        NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    student_id             uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    simplified_description text        NOT NULL,
    parent_hint            text,
    step_by_step           text[]      NOT NULL DEFAULT '{}',
    reason                 text,
    ai_generated           boolean     NOT NULL DEFAULT true,
    approved_by            uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX homework_variants_key ON homework_variants (homework_id, student_id);

CREATE TABLE homework_submissions (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id        uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    homework_id      uuid        NOT NULL REFERENCES homework (id) ON DELETE CASCADE,
    student_id       uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    status           text        NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'DONE', 'LATE', 'SKIPPED')),
    completed_at     timestamptz,
    parent_confirmed boolean     NOT NULL DEFAULT false,
    confirmed_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    attachments      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    teacher_feedback text,
    grade_value      numeric(5, 2),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX homework_submissions_key ON homework_submissions (homework_id, student_id);
CREATE INDEX homework_submissions_student_idx ON homework_submissions (student_id, status);

-- Ko'nikma / intellekt / xulq baholash
CREATE TABLE assessment_tasks (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id        uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    teacher_id       uuid        NOT NULL REFERENCES teachers (id) ON DELETE CASCADE,
    group_id         uuid REFERENCES groups (id) ON DELETE SET NULL,
    student_ids      uuid[]      NOT NULL DEFAULT '{}',
    title            text        NOT NULL,
    instruction      text        NOT NULL,
    category         text        NOT NULL
                                 CHECK (category IN ('COGNITIVE', 'INTEREST', 'BEHAVIOR', 'SOCIAL', 'CREATIVE')),
    expected_signals text[]      NOT NULL DEFAULT '{}',
    observation_guide text,
    observations     jsonb       NOT NULL DEFAULT '[]'::jsonb,  -- [{studentId, note}]
    ai_generated     boolean     NOT NULL DEFAULT false,
    status           text        NOT NULL DEFAULT 'DRAFT'
                                 CHECK (status IN ('DRAFT', 'ASSIGNED', 'COLLECTED', 'ANALYZED')),
    result_summary   text,
    due_date         date,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX assessment_tasks_school_idx ON assessment_tasks (school_id, created_at DESC);

CREATE TABLE skill_assessments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    student_id  uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    teacher_id  uuid REFERENCES teachers (id) ON DELETE SET NULL,
    task_id     uuid REFERENCES assessment_tasks (id) ON DELETE SET NULL,
    assessed_on date        NOT NULL DEFAULT CURRENT_DATE,
    category    text        NOT NULL
                            CHECK (category IN ('COGNITIVE', 'INTEREST', 'BEHAVIOR', 'SOCIAL', 'CREATIVE')),
    skills      jsonb       NOT NULL DEFAULT '[]'::jsonb,  -- [{key,label,score,note}]
    summary     text,
    source      text        NOT NULL DEFAULT 'OBSERVATION'
                            CHECK (source IN ('TEACHER_TASK', 'AI_ANALYSIS', 'OBSERVATION')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX skill_assessments_student_idx ON skill_assessments (student_id, assessed_on DESC);

CREATE TABLE interests (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id  uuid REFERENCES schools (id) ON DELETE CASCADE,
    student_id uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    key        text        NOT NULL,
    label      text        NOT NULL,
    description text,
    level      integer     NOT NULL DEFAULT 50 CHECK (level BETWEEN 0 AND 100),
    color_key  text        NOT NULL DEFAULT 'blue',
    icon_key   text        NOT NULL DEFAULT 'star',
    source     text        NOT NULL DEFAULT 'TEACHER'
                           CHECK (source IN ('TEACHER', 'AI', 'PARENT', 'SELF')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX interests_student_key ON interests (student_id, key);

CREATE TABLE cognitive_recommendations (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id        uuid REFERENCES schools (id) ON DELETE CASCADE,
    student_id       uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    teacher_id       uuid REFERENCES teachers (id) ON DELETE SET NULL,
    area             text        NOT NULL
                                 CHECK (area IN ('LOGIC', 'MEMORY', 'ATTENTION', 'LANGUAGE',
                                                 'SPATIAL', 'CREATIVITY', 'EMOTIONAL')),
    title            text        NOT NULL,
    description      text        NOT NULL,
    rationale        text        NOT NULL,
    steps            text[]      NOT NULL DEFAULT '{}',
    duration_minutes integer     NOT NULL DEFAULT 15,
    difficulty       text        NOT NULL DEFAULT 'NORMAL' CHECK (difficulty IN ('EASY', 'NORMAL', 'HARD')),
    status           text        NOT NULL DEFAULT 'SUGGESTED'
                                 CHECK (status IN ('SUGGESTED', 'ASSIGNED', 'IN_PROGRESS', 'DONE', 'DISMISSED')),
    assigned_to      text        NOT NULL DEFAULT 'PARENT' CHECK (assigned_to IN ('STUDENT', 'PARENT')),
    created_by_ai    boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX cognitive_recommendations_student_idx ON cognitive_recommendations (student_id, status);

-- Hisoblangan ko'rsatkich — kunlik job yozadi, dashboard o'qiydi
CREATE TABLE student_performance (
    student_id      uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    period          text        NOT NULL CHECK (period IN ('WEEK', 'MONTH', 'SEMESTER', 'YEAR')),
    school_id       uuid        NOT NULL REFERENCES schools (id) ON DELETE CASCADE,
    academic_score  numeric(5, 2) NOT NULL DEFAULT 0,
    skill_score     numeric(5, 2) NOT NULL DEFAULT 0,
    knowledge_score numeric(5, 2) NOT NULL DEFAULT 0,
    attendance_rate numeric(5, 2) NOT NULL DEFAULT 0,
    score_delta     numeric(5, 2) NOT NULL DEFAULT 0,
    by_subject      jsonb       NOT NULL DEFAULT '[]'::jsonb,
    by_topic        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    strengths       text[]      NOT NULL DEFAULT '{}',
    developing      text[]      NOT NULL DEFAULT '{}',
    needs_attention text[]      NOT NULL DEFAULT '{}',
    trend           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    computed_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, period)
);

CREATE INDEX student_performance_school_idx ON student_performance (school_id, period, academic_score);

CREATE TABLE achievements (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid REFERENCES schools (id) ON DELETE CASCADE,
    student_id  uuid        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    title       text        NOT NULL,
    description text,
    achieved_on date        NOT NULL DEFAULT CURRENT_DATE,
    icon_key    text        NOT NULL DEFAULT 'trophy',
    color_key   text        NOT NULL DEFAULT 'amber',
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX achievements_student_idx ON achievements (student_id, achieved_on DESC);
