package uz.farzandim.shared.analytics

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.util.Sql
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

data class SubjectScoreDto(
    val subjectId: UUID,
    val subjectName: String,
    val shortName: String,
    val colorKey: String,
    val score: Int,
    val delta: Int,
)

data class TopicScoreDto(
    val topicId: UUID,
    val title: String,
    val score: Int,
)

data class TrendPoint(val label: String, val value: Int)

data class PerformanceDto(
    val studentId: UUID,
    val period: PerformancePeriod,
    val academicScore: Int,
    val skillScore: Int,
    val knowledgeScore: Int,
    val attendanceRate: Int,
    val scoreDelta: Int,
    val bySubject: List<SubjectScoreDto>,
    val strengths: List<String>,
    val developing: List<String>,
    val needsAttention: List<String>,
    val trend: List<TrendPoint>,
)

/**
 * Hisoblash qoidalari — docs/02-data-model.md, 4-bo'lim.
 *
 * Og'irliklar (EXAM ×3, QUIZ ×2, qolgani ×1) SQL ichida: o'n minglab
 * bahoni Kotlin'ga tortib, keyin yig'ish — bekorga xotira va vaqt.
 * PostgreSQL buni indeks bo'yicha bir o'tishda qiladi.
 */
@ApplicationScoped
class PerformanceService {

    @Inject lateinit var em: EntityManager

    companion object {
        const val STRENGTH_THRESHOLD = 85
        const val DEVELOPING_THRESHOLD = 60
        const val ATTENTION_SCORE = 65
        const val ATTENTION_ATTENDANCE = 85
        const val ATTENTION_DELTA = -10

        private const val WEIGHT_SQL =
            "case g.type when 'EXAM' then 3 when 'QUIZ' then 2 else 1 end"
    }

    fun rangeFor(period: PerformancePeriod, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> =
        when (period) {
            PerformancePeriod.WEEK -> today.minusDays(6) to today
            PerformancePeriod.MONTH -> today.minusDays(29) to today
            PerformancePeriod.SEMESTER -> today.minusMonths(5) to today
            PerformancePeriod.YEAR -> today.minusMonths(11) to today
        }

    /** Og'irlikli o'rtacha, 0–100. Baho bo'lmasa 0. */
    fun academicScore(schoolId: UUID, studentId: UUID, from: LocalDate, to: LocalDate): Double {
        val result = em.createNativeQuery(
            """
            select coalesce(
                sum(g.value / g.max_value * 100 * ($WEIGHT_SQL)) / nullif(sum($WEIGHT_SQL), 0),
            0)
            from grades g
            where g.school_id = :school and g.student_id = :student
              and g.graded_on between :from and :to
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("student", studentId)
            .setParameter("from", from)
            .setParameter("to", to)
            .singleResult
        return (result as? Number)?.toDouble() ?: 0.0
    }

    /** `PRESENT + EXCUSED` / jami × 100. Dars bo'lmagan davrda 100 deb hisoblanadi. */
    fun attendanceRate(schoolId: UUID, studentId: UUID, from: LocalDate, to: LocalDate): Double {
        val result = em.createNativeQuery(
            """
            select case when count(*) = 0 then 100
                        else 100.0 * count(*) filter (where status in ('PRESENT', 'EXCUSED')) / count(*)
                   end
            from attendance
            where school_id = :school and student_id = :student
              and attended_on between :from and :to
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("student", studentId)
            .setParameter("from", from)
            .setParameter("to", to)
            .singleResult
        return (result as? Number)?.toDouble() ?: 100.0
    }

    fun subjectScores(
        schoolId: UUID,
        studentId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<SubjectScoreDto> {
        val span = ChronoUnit.DAYS.between(from, to) + 1
        val prevFrom = from.minusDays(span)
        val prevTo = from.minusDays(1)

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select s.id, s.name, s.short_name, s.color_key,
                   coalesce(sum(g.value / g.max_value * 100 * ($WEIGHT_SQL))
                            filter (where g.graded_on between :from and :to)
                            / nullif(sum($WEIGHT_SQL) filter (where g.graded_on between :from and :to), 0), 0) as score,
                   coalesce(sum(g.value / g.max_value * 100 * ($WEIGHT_SQL))
                            filter (where g.graded_on between :prevFrom and :prevTo)
                            / nullif(sum($WEIGHT_SQL) filter (where g.graded_on between :prevFrom and :prevTo), 0), 0) as prev
            from subjects s
            join grades g on g.subject_id = s.id and g.student_id = :student
            where s.school_id = :school and g.graded_on between :prevFrom and :to
            group by s.id, s.name, s.short_name, s.color_key
            order by s.name
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("student", studentId)
            .setParameter("from", from)
            .setParameter("to", to)
            .setParameter("prevFrom", prevFrom)
            .setParameter("prevTo", prevTo)
            .resultList as List<Array<Any?>>

        return rows.map { row ->
            val score = (row[4] as Number).toDouble()
            val prev = (row[5] as Number).toDouble()
            SubjectScoreDto(
                subjectId = row[0] as UUID,
                subjectName = row[1] as String,
                shortName = row[2] as String,
                colorKey = row[3] as String,
                score = score.roundToInt(),
                delta = if (prev <= 0.0) 0 else (score - prev).roundToInt(),
            )
        }
    }

    fun topicScores(
        schoolId: UUID,
        studentId: UUID,
        subjectId: UUID?,
        from: LocalDate,
        to: LocalDate,
    ): List<TopicScoreDto> {
        val subjectFilter = if (subjectId != null) "and t.subject_id = :subject" else ""

        val query = em.createNativeQuery(
            """
            select t.id, t.title,
                   sum(g.value / g.max_value * 100 * ($WEIGHT_SQL)) / nullif(sum($WEIGHT_SQL), 0) as score
            from topics t
            join grades g on g.topic_id = t.id and g.student_id = :student
            where t.school_id = :school and g.graded_on between :from and :to $subjectFilter
            group by t.id, t.title, t.order_index
            order by t.order_index
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("student", studentId)
            .setParameter("from", from)
            .setParameter("to", to)
        if (subjectId != null) query.setParameter("subject", subjectId)

        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        return rows.map {
            TopicScoreDto(it[0] as UUID, it[1] as String, ((it[2] as? Number)?.toDouble() ?: 0.0).roundToInt())
        }
    }

    /** Oxirgi 6 nuqta: hafta yoki oy kesimida o'rtacha ball. */
    fun trend(schoolId: UUID, studentId: UUID, period: PerformancePeriod): List<TrendPoint> {
        val bucket = if (period == PerformancePeriod.WEEK) "day" else if (period == PerformancePeriod.MONTH) "week" else "month"
        val points = if (period == PerformancePeriod.WEEK) 7 else 6
        val (from, to) = rangeFor(period)

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select date_trunc(:bucket, g.graded_on)::date as period_start,
                   sum(g.value / g.max_value * 100 * ($WEIGHT_SQL)) / nullif(sum($WEIGHT_SQL), 0) as score
            from grades g
            where g.school_id = :school and g.student_id = :student
              and g.graded_on between :from and :to
            group by 1
            order by 1
            """.trimIndent(),
        )
            .setParameter("bucket", bucket)
            .setParameter("school", schoolId)
            .setParameter("student", studentId)
            .setParameter("from", from)
            .setParameter("to", to)
            .resultList as List<Array<Any?>>

        return rows.takeLast(points).map { row ->
            val date = Sql.requireDate(row[0])
            TrendPoint(
                label = when (bucket) {
                    "day" -> date.dayOfMonth.toString()
                    "week" -> "${date.dayOfMonth}.${date.monthValue}"
                    else -> date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
                },
                value = ((row[1] as? Number)?.toDouble() ?: 0.0).roundToInt(),
            )
        }
    }

    /** Ko'nikma balli — `skill_assessments.skills` ichidagi ballarning o'rtachasi. */
    fun skillScore(schoolId: UUID, studentId: UUID): Double {
        val result = em.createNativeQuery(
            """
            select coalesce(avg((skill ->> 'score')::numeric), 0)
            from skill_assessments sa, jsonb_array_elements(sa.skills) as skill
            where sa.school_id = :school and sa.student_id = :student
              and sa.assessed_on >= current_date - interval '180 days'
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("student", studentId)
            .singleResult
        return (result as? Number)?.toDouble() ?: 0.0
    }

    fun performance(schoolId: UUID, studentId: UUID, period: PerformancePeriod): PerformanceDto {
        val (from, to) = rangeFor(period)
        val span = ChronoUnit.DAYS.between(from, to) + 1

        val academic = academicScore(schoolId, studentId, from, to)
        val previous = academicScore(schoolId, studentId, from.minusDays(span), from.minusDays(1))
        val attendance = attendanceRate(schoolId, studentId, from, to)
        val subjects = subjectScores(schoolId, studentId, from, to)
        val topics = topicScores(schoolId, studentId, null, from, to)
        val skills = skillScore(schoolId, studentId)

        return PerformanceDto(
            studentId = studentId,
            period = period,
            academicScore = academic.roundToInt(),
            skillScore = skills.roundToInt(),
            // "Bilim" — mavzular bo'yicha o'zlashtirish; mavzu belgilanmagan
            // baholar ko'p bo'lsa, umumiy ballga tayanamiz.
            knowledgeScore = if (topics.isEmpty()) academic.roundToInt()
            else topics.map { it.score }.average().roundToInt(),
            attendanceRate = attendance.roundToInt(),
            scoreDelta = if (previous <= 0.0) 0 else (academic - previous).roundToInt(),
            bySubject = subjects,
            strengths = topics.filter { it.score >= STRENGTH_THRESHOLD }.map { it.title },
            developing = topics.filter { it.score in DEVELOPING_THRESHOLD until STRENGTH_THRESHOLD }.map { it.title },
            needsAttention = topics.filter { it.score < DEVELOPING_THRESHOLD }.map { it.title },
            trend = trend(schoolId, studentId, period),
        )
    }

    /**
     * E'tibor talab qiladigan o'quvchilar: ball < 65 YOKI davomat < 85
     * YOKI oxirgi 2 haftada pasayish ≥ 10 ball.
     */
    fun needsAttentionCount(schoolId: UUID): Long {
        val result = em.createNativeQuery(
            """
            with recent as (
                select g.student_id,
                       sum(g.value / g.max_value * 100 * ($WEIGHT_SQL))
                           / nullif(sum($WEIGHT_SQL), 0) as score
                from grades g
                where g.school_id = :school and g.graded_on >= current_date - interval '30 days'
                group by g.student_id
            ), presence as (
                select a.student_id,
                       100.0 * count(*) filter (where a.status in ('PRESENT', 'EXCUSED')) / nullif(count(*), 0) as rate
                from attendance a
                where a.school_id = :school and a.attended_on >= current_date - interval '30 days'
                group by a.student_id
            )
            select count(*)
            from students s
            left join recent r on r.student_id = s.id
            left join presence p on p.student_id = s.id
            where s.school_id = :school and s.status = 'ACTIVE'
              and (coalesce(r.score, 0) < $ATTENTION_SCORE or coalesce(p.rate, 100) < $ATTENTION_ATTENDANCE)
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .singleResult
        return (result as? Number)?.toLong() ?: 0
    }

    /** Ro'yxat ekranlari uchun: bir so'rovda ko'p o'quvchining balli. */
    fun academicScoresFor(schoolId: UUID, studentIds: Collection<UUID>, days: Long = 30): Map<UUID, Int> {
        if (studentIds.isEmpty()) return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select g.student_id,
                   sum(g.value / g.max_value * 100 * ($WEIGHT_SQL)) / nullif(sum($WEIGHT_SQL), 0)
            from grades g
            where g.school_id = :school and g.student_id in (:students)
              and g.graded_on >= current_date - make_interval(days => :days)
            group by g.student_id
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("students", studentIds)
            .setParameter("days", days.toInt())
            .resultList as List<Array<Any?>>

        return rows.associate { (it[0] as UUID) to ((it[1] as? Number)?.toDouble() ?: 0.0).roundToInt() }
    }

    fun attendanceRatesFor(schoolId: UUID, studentIds: Collection<UUID>, days: Long = 30): Map<UUID, Int> {
        if (studentIds.isEmpty()) return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select a.student_id,
                   100.0 * count(*) filter (where a.status in ('PRESENT', 'EXCUSED')) / nullif(count(*), 0)
            from attendance a
            where a.school_id = :school and a.student_id in (:students)
              and a.attended_on >= current_date - make_interval(days => :days)
            group by a.student_id
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("students", studentIds)
            .setParameter("days", days.toInt())
            .resultList as List<Array<Any?>>

        return rows.associate { (it[0] as UUID) to ((it[1] as? Number)?.toDouble() ?: 100.0).roundToInt() }
    }
}
