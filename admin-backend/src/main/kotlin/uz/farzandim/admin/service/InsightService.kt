package uz.farzandim.admin.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import uz.farzandim.admin.dto.AlertDto
import uz.farzandim.admin.dto.DailyActivityDto
import uz.farzandim.admin.dto.GradeLevelCountDto
import uz.farzandim.admin.dto.GroupRowDto
import uz.farzandim.admin.dto.PlatformStatsDto
import uz.farzandim.admin.dto.PlatformUserDto
import uz.farzandim.admin.dto.SchoolMetricsDto
import uz.farzandim.admin.dto.SchoolOverviewDto
import uz.farzandim.admin.dto.StudentRowDto
import uz.farzandim.admin.dto.TeacherRowDto
import uz.farzandim.admin.dto.TrendPointDto
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.Page
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.JobStatus
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.SchoolStatus
import uz.farzandim.shared.domain.SubscriptionPlanCode
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.repo.AiJobRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.util.Sql
import java.time.YearMonth
import java.util.UUID

/**
 * Platforma va maktab statistikasi — faqat o'qish.
 *
 * Hisoblash qoidalari docs/02-data-model.md (4-bo'lim) bilan bir xil:
 * davomat — `PRESENT + EXCUSED` / jami, o'rtacha ball — 0–100 ga
 * normallashgan og'irlikli o'rtacha (EXAM ×3, QUIZ ×2, qolgani ×1).
 *
 * Maktab ichidagi shaxsiy ma'lumot (o'quvchi ismlari, hisoblar) har safar
 * ko'rilganda audit jurnaliga yoziladi — platforma jamoasi bolalar
 * ma'lumotiga qachon va nima uchun qaraganini keyin tekshirish mumkin.
 */
@ApplicationScoped
class InsightService {

    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var jobs: AiJobRepository
    @Inject lateinit var schoolAdmin: SchoolAdminService
    @Inject lateinit var auditRecorder: AuditRecorder
    @Inject lateinit var em: EntityManager

    /* ----------------------------- Bosh sahifa ------------------------------ */

    fun platformStats(): PlatformStatsDto {
        fun count(sql: String): Long = em.createNativeQuery(sql).singleResult.asLong()

        return PlatformStatsDto(
            schools = count("select count(*) from schools"),
            activeSchools = count("select count(*) from schools where status = 'ACTIVE'"),
            suspendedSchools = count("select count(*) from schools where status = 'SUSPENDED'"),
            archivedSchools = count("select count(*) from schools where status = 'ARCHIVED'"),
            newSchools30d = count("select count(*) from schools where created_at >= now() - interval '30 days'"),
            students = count("select count(*) from students where status = 'ACTIVE'"),
            teachers = count("select count(*) from teachers"),
            parents = count("select count(*) from parents where school_id is not null"),
            parentsActivated = count(
                "select count(*) from parents p join users u on u.id = p.user_id " +
                    "where p.school_id is not null and u.last_login_at is not null",
            ),
            activeUsers30d = count("select count(*) from users where last_login_at >= now() - interval '30 days'"),
            expiringIn30Days = count(
                "select count(*) from schools where status = 'ACTIVE' " +
                    "and subscription_until between current_date and current_date + 30",
            ),
            expired = count("select count(*) from schools where status = 'ACTIVE' and subscription_until < current_date"),
            monthlyRecurringUzs = count(BillingService.MRR_SQL),
            unpaidUzs = count(
                "select coalesce(sum(amount_uzs), 0) from school_subscriptions where payment_status = 'UNPAID' and status <> 'CANCELLED'",
            ),
            paidThisMonthUzs = count(
                "select coalesce(sum(amount_uzs), 0) from school_subscriptions " +
                    "where payment_status = 'PAID' and date_trunc('month', paid_on) = date_trunc('month', current_date)",
            ),
            aiCallsToday = count("select coalesce(sum(calls), 0) from ai_usage_daily where usage_date = current_date"),
            aiCostTodayUzs = count(
                "select coalesce(sum(cost_micros), 0) / 1000000 from ai_usage_daily where usage_date = current_date",
            ),
            aiCalls30d = count("select coalesce(sum(calls), 0) from ai_usage_daily where usage_date >= current_date - 30"),
            aiCost30dUzs = count(
                "select coalesce(sum(cost_micros), 0) / 1000000 from ai_usage_daily where usage_date >= current_date - 30",
            ),
            jobsQueued = jobs.countByStatus(JobStatus.QUEUED),
            jobsFailed24h = count(
                "select count(*) from ai_jobs where status = 'FAILED' and updated_at >= now() - interval '24 hours'",
            ),
        )
    }

    /** Oylar kesimida o'sish: maktablar, o'quvchilar, pul, AI. */
    fun trends(months: Int): List<TrendPointDto> {
        val first = YearMonth.now().minusMonths((months - 1).toLong())
        val from = first.atDay(1)

        fun monthly(sql: String): Map<String, Long> {
            @Suppress("UNCHECKED_CAST")
            val rows = em.createNativeQuery(sql).setParameter("from", from).resultList as List<Array<Any?>>
            return rows.associate { it[0] as String to it[1].asLong() }
        }

        val newSchools = monthly(
            "select to_char(created_at, 'YYYY-MM'), count(*) from schools where created_at >= :from group by 1",
        )
        val newStudents = monthly(
            "select to_char(created_at, 'YYYY-MM'), count(*) from students where created_at >= :from group by 1",
        )
        val invoiced = monthly(
            "select to_char(starts_on, 'YYYY-MM'), coalesce(sum(amount_uzs), 0) from school_subscriptions " +
                "where status <> 'CANCELLED' and starts_on >= :from group by 1",
        )
        val paid = monthly(
            "select to_char(paid_on, 'YYYY-MM'), coalesce(sum(amount_uzs), 0) from school_subscriptions " +
                "where payment_status = 'PAID' and paid_on >= :from group by 1",
        )
        val aiCalls = monthly(
            "select to_char(usage_date, 'YYYY-MM'), coalesce(sum(calls), 0) from ai_usage_daily where usage_date >= :from group by 1",
        )
        val aiCost = monthly(
            "select to_char(usage_date, 'YYYY-MM'), coalesce(sum(cost_micros), 0) / 1000000 from ai_usage_daily " +
                "where usage_date >= :from group by 1",
        )
        var running = em.createNativeQuery("select count(*) from schools where created_at < :from")
            .setParameter("from", from).singleResult.asLong()

        return (0 until months).map { i ->
            val key = first.plusMonths(i.toLong()).toString()
            val added = newSchools[key] ?: 0
            running += added
            TrendPointDto(
                month = key,
                newSchools = added,
                totalSchools = running,
                newStudents = newStudents[key] ?: 0,
                invoicedUzs = invoiced[key] ?: 0,
                paidUzs = paid[key] ?: 0,
                aiCalls = aiCalls[key] ?: 0,
                aiCostUzs = aiCost[key] ?: 0,
            )
        }
    }

    /**
     * "Diqqat talab qiladi" — bosh sahifadagi ish ro'yxati.
     * Har bir signal aniq harakatga olib borishi kerak: qo'ng'iroq qilish,
     * to'lovni so'rash, ishga tushirishda yordam berish.
     */
    fun alerts(): List<AlertDto> {
        val alerts = mutableListOf<AlertDto>()

        @Suppress("UNCHECKED_CAST")
        fun rows(sql: String): List<Array<Any?>> = em.createNativeQuery(sql).resultList as List<Array<Any?>>

        rows(
            """
            select id, name, subscription_until - current_date from schools
            where status = 'ACTIVE' and subscription_until < current_date
            order by subscription_until
            """.trimIndent(),
        ).forEach {
            alerts += AlertDto(
                "SUBSCRIPTION_EXPIRED", "HIGH", it[0] as UUID, it[1] as String,
                "Obuna ${-it[2].asLong()} kun oldin tugagan — maktab kira olmayapti", -it[2].asLong(),
            )
        }

        rows(
            """
            select id, name, subscription_until - current_date from schools
            where status = 'ACTIVE' and subscription_until between current_date and current_date + 30
            order by subscription_until
            """.trimIndent(),
        ).forEach {
            val days = it[2].asLong()
            alerts += AlertDto(
                "SUBSCRIPTION_EXPIRING", if (days <= 7) "HIGH" else "MEDIUM", it[0] as UUID, it[1] as String,
                "Obuna $days kundan keyin tugaydi", days,
            )
        }

        rows(
            """
            select s.id, s.name, sum(ss.amount_uzs), min(ss.starts_on)
            from school_subscriptions ss join schools s on s.id = ss.school_id
            where ss.payment_status = 'UNPAID' and ss.status <> 'CANCELLED' and s.status <> 'ARCHIVED'
              and ss.starts_on <= current_date - 14
            group by s.id, s.name
            order by 3 desc
            """.trimIndent(),
        ).forEach {
            alerts += AlertDto(
                "UNPAID", "MEDIUM", it[0] as UUID, it[1] as String,
                "To'lanmagan obuna (14 kundan ortiq)", it[2].asLong(),
            )
        }

        // Ulanganiga 14 kun bo'ldi, lekin hali o'quvchi yoki jadval yo'q — yordam kerak.
        rows(
            """
            select s.id, s.name, ${OnboardingSteps.DONE_SQL.replace("\n", " ")}
            from schools s
            where s.status = 'ACTIVE' and s.created_at <= now() - interval '14 days'
            """.trimIndent(),
        ).filter { it[2].asLong() < 6 }.forEach {
            alerts += AlertDto(
                "ONBOARDING_STALLED", "MEDIUM", it[0] as UUID, it[1] as String,
                "Ishga tushirish to'xtab qolgan: ${it[2].asLong()}/${OnboardingSteps.ALL.size} bosqich", it[2].asLong(),
            )
        }

        rows(
            """
            select s.id, s.name from schools s
            where s.status = 'ACTIVE' and s.created_at <= now() - interval '14 days'
              and not exists (select 1 from users u where u.school_id = s.id and u.last_login_at >= now() - interval '14 days')
            """.trimIndent(),
        ).forEach {
            alerts += AlertDto(
                "INACTIVE", "MEDIUM", it[0] as UUID, it[1] as String,
                "14 kundan beri hech kim tizimga kirmagan", null,
            )
        }

        rows(
            """
            select s.id, s.name from schools s
            where s.status = 'ACTIVE' and not exists (
                select 1 from users u where u.school_id = s.id and u.role = 'SCHOOL_ADMIN' and u.status <> 'BLOCKED'
                  and (u.admin_level is null or u.admin_level = 'SUPER'))
            """.trimIndent(),
        ).forEach {
            alerts += AlertDto(
                "NO_ADMIN", "HIGH", it[0] as UUID, it[1] as String,
                "Faol superadmin yo'q — maktab o'z adminlarini boshqara olmaydi", null,
            )
        }

        rows(
            """
            select s.id, s.name, sum(a.calls), s.ai_daily_limit
            from ai_usage_daily a join schools s on s.id = a.school_id
            where a.usage_date = current_date and s.ai_daily_limit > 0
            group by s.id, s.name, s.ai_daily_limit
            having sum(a.calls) >= s.ai_daily_limit * 0.9
            """.trimIndent(),
        ).forEach {
            alerts += AlertDto(
                "AI_QUOTA", "LOW", it[0] as UUID, it[1] as String,
                "Bugungi AI limiti deyarli tugagan: ${it[2].asLong()}/${it[3].asLong()}", it[2].asLong(),
            )
        }

        val failed = em.createNativeQuery(
            "select count(*) from ai_jobs where status = 'FAILED' and updated_at >= now() - interval '24 hours'",
        ).singleResult.asLong()
        if (failed > 0) {
            alerts += AlertDto("JOBS_FAILED", if (failed >= 10) "HIGH" else "LOW", null, null, "Oxirgi 24 soatda $failed ta AI job yiqildi", failed)
        }

        val order = mapOf("HIGH" to 0, "MEDIUM" to 1, "LOW" to 2)
        return alerts.sortedBy { order[it.severity] ?: 3 }
    }

    /** Maktablarni yonma-yon taqqoslash (arxivlanganlardan tashqari). */
    fun schoolMetrics(): List<SchoolMetricsDto> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select s.id, s.name, s.status, s.subscription_plan,
              (select count(*) from students st where st.school_id = s.id and st.status = 'ACTIVE'),
              (select count(*) from teachers t where t.school_id = s.id),
              (select count(*) from groups g where g.school_id = s.id and not g.archived),
              (select count(*) from parents p join users u on u.id = p.user_id
                 where p.school_id = s.id and u.last_login_at is not null),
              (select count(*) from users u where u.school_id = s.id and u.last_login_at >= now() - interval '30 days'),
              (select count(*) from lessons l where l.school_id = s.id and l.status = 'DONE' and l.lesson_date >= current_date - 30),
              (select count(*) from lessons l where l.school_id = s.id and l.summary_status = 'SENT_TO_PARENTS'
                 and l.lesson_date >= current_date - 30),
              (select count(*) from grades g where g.school_id = s.id and g.graded_on >= current_date - 30),
              (select count(*) from homework h where h.school_id = s.id and h.created_at >= now() - interval '30 days'),
              $ATTENDANCE_30D_SQL,
              $SCORE_30D_SQL,
              (select coalesce(sum(a.calls), 0) from ai_usage_daily a where a.school_id = s.id and a.usage_date >= current_date - 30),
              (select coalesce(sum(a.cost_micros), 0) / 1000000 from ai_usage_daily a
                 where a.school_id = s.id and a.usage_date >= current_date - 30)
            from schools s
            where s.status <> 'ARCHIVED'
            order by s.name
            """.trimIndent(),
        ).resultList as List<Array<Any?>>

        return rows.map {
            SchoolMetricsDto(
                schoolId = it[0] as UUID,
                name = it[1] as String,
                status = SchoolStatus.valueOf(it[2] as String),
                plan = SubscriptionPlanCode.valueOf(it[3] as String),
                students = it[4].asLong(),
                teachers = it[5].asLong(),
                groups = it[6].asLong(),
                parentsActivated = it[7].asLong(),
                activeUsers30d = it[8].asLong(),
                lessonsDone30d = it[9].asLong(),
                summariesSent30d = it[10].asLong(),
                grades30d = it[11].asLong(),
                homework30d = it[12].asLong(),
                attendanceRate30d = it[13].asDoubleOrNull().round1(),
                averageScore30d = it[14].asDoubleOrNull().round1(),
                aiCalls30d = it[15].asLong(),
                aiCost30dUzs = it[16].asLong(),
            )
        }
    }

    /* ---------------------------- Maktab ichidan ---------------------------- */

    fun overview(schoolId: UUID): SchoolOverviewDto {
        requireSchool(schoolId)

        @Suppress("UNCHECKED_CAST")
        val r = em.createNativeQuery(
            """
            select
              (select count(*) from students st where st.school_id = s.id and st.status = 'ACTIVE'),
              (select count(*) from teachers t where t.school_id = s.id),
              (select count(*) from groups g where g.school_id = s.id and not g.archived),
              (select count(*) from subjects x where x.school_id = s.id and not x.archived),
              (select count(*) from parents p where p.school_id = s.id),
              (select count(*) from parents p join users u on u.id = p.user_id
                 where p.school_id = s.id and u.last_login_at is not null),
              (select count(*) from users u where u.school_id = s.id and u.role = 'SCHOOL_ADMIN'),
              (select count(*) from users u where u.school_id = s.id and u.role = 'SCHOOL_ADMIN'
                 and u.last_login_at >= now() - interval '30 days'),
              (select count(*) from users u where u.school_id = s.id and u.role = 'TEACHER'
                 and u.last_login_at >= now() - interval '30 days'),
              (select count(*) from users u where u.school_id = s.id and u.role = 'PARENT'
                 and u.last_login_at >= now() - interval '30 days'),
              (select count(*) from lessons l where l.school_id = s.id and l.status = 'DONE' and l.lesson_date >= current_date - 30),
              (select count(*) from lessons l where l.school_id = s.id and l.status <> 'CANCELLED'
                 and l.lesson_date between current_date - 30 and current_date),
              (select count(*) from lessons l where l.school_id = s.id and l.summary_status = 'SENT_TO_PARENTS'
                 and l.lesson_date >= current_date - 30),
              (select count(*) from grades g where g.school_id = s.id and g.graded_on >= current_date - 30),
              (select count(*) from homework h where h.school_id = s.id and h.created_at >= now() - interval '30 days'),
              (select count(*) from messages m where m.school_id = s.id and m.sent_at >= now() - interval '30 days'),
              $ATTENDANCE_30D_SQL,
              $SCORE_30D_SQL,
              (select coalesce(sum(a.calls), 0) from ai_usage_daily a where a.school_id = s.id and a.usage_date >= current_date - 30),
              (select coalesce(sum(a.cost_micros), 0) / 1000000 from ai_usage_daily a
                 where a.school_id = s.id and a.usage_date >= current_date - 30)
            from schools s where s.id = :s
            """.trimIndent(),
        ).setParameter("s", schoolId).singleResult as Array<Any?>

        @Suppress("UNCHECKED_CAST")
        val gradeLevels = (
            em.createNativeQuery(
                """
                select grade_level, count(*) from students
                where school_id = :s and status = 'ACTIVE'
                group by grade_level order by grade_level
                """.trimIndent(),
            ).setParameter("s", schoolId).resultList as List<Array<Any?>>
            ).map { GradeLevelCountDto((it[0] as Number).toInt(), it[1].asLong()) }

        return SchoolOverviewDto(
            schoolId = schoolId,
            students = r[0].asLong(),
            teachers = r[1].asLong(),
            groups = r[2].asLong(),
            subjects = r[3].asLong(),
            parents = r[4].asLong(),
            parentsActivated = r[5].asLong(),
            admins = r[6].asLong(),
            activeAdmins30d = r[7].asLong(),
            activeTeachers30d = r[8].asLong(),
            activeParents30d = r[9].asLong(),
            lessonsDone30d = r[10].asLong(),
            lessonsPlanned30d = r[11].asLong(),
            summariesSent30d = r[12].asLong(),
            grades30d = r[13].asLong(),
            homework30d = r[14].asLong(),
            messages30d = r[15].asLong(),
            attendanceRate30d = r[16].asDoubleOrNull().round1(),
            averageScore30d = r[17].asDoubleOrNull().round1(),
            aiCalls30d = r[18].asLong(),
            aiCost30dUzs = r[19].asLong(),
            gradeLevels = gradeLevels,
            daily = daily(schoolId),
            onboarding = schoolAdmin.onboarding(schoolId),
        )
    }

    private fun daily(schoolId: UUID): List<DailyActivityDto> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select d::date,
              (select count(*) from lessons l where l.school_id = :s and l.lesson_date = d::date and l.status = 'DONE'),
              (select count(*) from grades g where g.school_id = :s and g.graded_on = d::date),
              (select count(*) from attendance a where a.school_id = :s and a.attended_on = d::date),
              (select count(*) from homework h where h.school_id = :s and h.created_at::date = d::date),
              (select coalesce(sum(u.calls), 0) from ai_usage_daily u where u.school_id = :s and u.usage_date = d::date)
            from generate_series(current_date - 29, current_date, interval '1 day') d
            order by 1
            """.trimIndent(),
        ).setParameter("s", schoolId).resultList as List<Array<Any?>>

        return rows.map {
            DailyActivityDto(Sql.requireDate(it[0]), it[1].asLong(), it[2].asLong(), it[3].asLong(), it[4].asLong(), it[5].asLong())
        }
    }

    fun students(schoolId: UUID, q: String?, groupId: UUID?, status: String?, page: PageRequest): Page<StudentRowDto> {
        requireSchool(schoolId)
        val conditions = mutableListOf("st.school_id = :s")
        val params = mutableMapOf<String, Any>("s" to schoolId)
        if (!q.isNullOrBlank()) {
            // `search_text` + pg_trgm indeksi (V5)
            conditions += "st.search_text like :q"
            params["q"] = "%${q.trim().lowercase()}%"
        }
        groupId?.let {
            conditions += "st.group_id = :g"
            params["g"] = it
        }
        val statusValue = status?.uppercase()?.takeIf { it in setOf("ACTIVE", "TRANSFERRED", "GRADUATED") }
        statusValue?.let {
            conditions += "st.status = :status"
            params["status"] = it
        }
        val where = conditions.joinToString(" and ")

        val countQuery = em.createNativeQuery("select count(*) from students st where $where")
        params.forEach { (k, v) -> countQuery.setParameter(k, v) }
        val total = countQuery.singleResult.asLong()

        auditRecorder.record(
            "SCHOOL_DATA_VIEWED", "SCHOOL", schoolId.toString(), schoolId,
            mapOf("section" to "students", "q" to q, "page" to page.page),
        )
        if (total == 0L) return Page.empty(page.page, page.size)

        val query = em.createNativeQuery(
            """
            select st.id, st.last_name || ' ' || st.first_name, st.grade_level, st.group_id, g.name, st.status,
                   st.gender, st.birth_date, st.enrolled_at,
                   (select count(*) from student_parents sp where sp.student_id = st.id),
                   exists (select 1 from student_parents sp join parents p on p.id = sp.parent_id
                           join users u on u.id = p.user_id where sp.student_id = st.id and u.last_login_at is not null),
                   (select case when count(*) = 0 then null
                           else 100.0 * count(*) filter (where a.status in ('PRESENT', 'EXCUSED')) / count(*) end
                      from attendance a where a.student_id = st.id and a.attended_on >= current_date - 30),
                   (select sum(gr.value / gr.max_value * 100 * case gr.type when 'EXAM' then 3 when 'QUIZ' then 2 else 1 end)
                           / nullif(sum(case gr.type when 'EXAM' then 3 when 'QUIZ' then 2 else 1 end), 0)
                      from grades gr where gr.student_id = st.id and gr.graded_on >= current_date - 30)
            from students st left join groups g on g.id = st.group_id
            where $where
            order by st.last_name, st.first_name
            limit :limit offset :offset
            """.trimIndent(),
        )
        params.forEach { (k, v) -> query.setParameter(k, v) }
        query.setParameter("limit", page.size)
        query.setParameter("offset", page.offset)

        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        val items = rows.map {
            StudentRowDto(
                id = it[0] as UUID,
                fullName = it[1] as String,
                gradeLevel = (it[2] as Number).toInt(),
                groupId = it[3] as UUID?,
                groupName = it[4] as String?,
                status = it[5] as String,
                gender = it[6] as String?,
                birthDate = Sql.date(it[7]),
                enrolledAt = Sql.requireDate(it[8]),
                parentsCount = it[9].asLong(),
                parentActivated = it[10] as? Boolean ?: false,
                attendanceRate30d = it[11].asDoubleOrNull().round1(),
                averageScore30d = it[12].asDoubleOrNull().round1(),
            )
        }
        return Page(items, page.page, page.size, total)
    }

    fun teachers(schoolId: UUID): List<TeacherRowDto> {
        requireSchool(schoolId)

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select t.id, u.id, u.last_name || ' ' || u.first_name, u.username, u.phone, u.status, u.last_login_at,
                   coalesce((select string_agg(distinct sb.name, '|') from teacher_assignments ta
                             join subjects sb on sb.id = ta.subject_id where ta.teacher_id = t.id), ''),
                   coalesce((select string_agg(distinct g.name, '|') from teacher_assignments ta
                             join groups g on g.id = ta.group_id where ta.teacher_id = t.id), ''),
                   (select count(*) from lessons l where l.teacher_id = t.id and l.status = 'DONE'
                      and l.lesson_date >= current_date - 30),
                   (select count(*) from grades gr where gr.teacher_id = t.id and gr.graded_on >= current_date - 30)
            from teachers t join users u on u.id = t.user_id
            where t.school_id = :s
            order by u.last_name, u.first_name
            """.trimIndent(),
        ).setParameter("s", schoolId).resultList as List<Array<Any?>>

        auditRecorder.record("SCHOOL_DATA_VIEWED", "SCHOOL", schoolId.toString(), schoolId, mapOf("section" to "teachers"))
        return rows.map {
            TeacherRowDto(
                id = it[0] as UUID,
                userId = it[1] as UUID,
                fullName = it[2] as String,
                username = it[3] as String?,
                phone = it[4] as String?,
                status = UserStatus.valueOf(it[5] as String),
                lastLoginAt = Sql.instant(it[6]),
                subjects = (it[7] as String).split('|').filter { s -> s.isNotBlank() }.sorted(),
                groups = (it[8] as String).split('|').filter { s -> s.isNotBlank() }.sorted(),
                lessonsDone30d = it[9].asLong(),
                grades30d = it[10].asLong(),
            )
        }
    }

    fun groups(schoolId: UUID): List<GroupRowDto> {
        requireSchool(schoolId)

        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select g.id, g.name, g.grade_level, g.academic_year,
                   (select u.last_name || ' ' || u.first_name from teachers t join users u on u.id = t.user_id
                      where t.id = g.class_teacher_id),
                   (select count(*) from students st where st.group_id = g.id and st.status = 'ACTIVE'),
                   (select case when count(*) = 0 then null
                           else 100.0 * count(*) filter (where a.status in ('PRESENT', 'EXCUSED')) / count(*) end
                      from attendance a join lessons l on l.id = a.lesson_id
                      where l.group_id = g.id and a.attended_on >= current_date - 30),
                   (select sum(gr.value / gr.max_value * 100 * case gr.type when 'EXAM' then 3 when 'QUIZ' then 2 else 1 end)
                           / nullif(sum(case gr.type when 'EXAM' then 3 when 'QUIZ' then 2 else 1 end), 0)
                      from grades gr join students st on st.id = gr.student_id
                      where st.group_id = g.id and gr.graded_on >= current_date - 30)
            from groups g
            where g.school_id = :s and not g.archived
            order by g.grade_level, g.name
            """.trimIndent(),
        ).setParameter("s", schoolId).resultList as List<Array<Any?>>

        return rows.map {
            GroupRowDto(
                id = it[0] as UUID,
                name = it[1] as String,
                gradeLevel = (it[2] as Number).toInt(),
                academicYear = it[3] as String,
                classTeacherName = it[4] as String?,
                studentCount = it[5].asLong(),
                attendanceRate30d = it[6].asDoubleOrNull().round1(),
                averageScore30d = it[7].asDoubleOrNull().round1(),
            )
        }
    }

    fun schoolUsers(
        schoolId: UUID,
        role: Role?,
        q: String?,
        status: UserStatus?,
        page: PageRequest,
    ): Page<PlatformUserDto> {
        val school = requireSchool(schoolId)
        val conditions = mutableListOf("schoolId = :school")
        val params = mutableMapOf<String, Any>("school" to schoolId)
        role?.let {
            conditions += "role = :role"
            params["role"] = it
        }
        status?.let {
            conditions += "status = :status"
            params["status"] = it
        }
        if (!q.isNullOrBlank()) {
            conditions += "(lower(firstName) like :q or lower(lastName) like :q or lower(username) like :q " +
                "or lower(email) like :q or phone like :q)"
            params["q"] = "%${q.trim().lowercase()}%"
        }
        val query = users.find("${conditions.joinToString(" and ")} order by role, lastName, firstName", params)
        val total = query.count()

        auditRecorder.record(
            "SCHOOL_DATA_VIEWED", "SCHOOL", schoolId.toString(), schoolId,
            mapOf("section" to "users", "role" to role?.name, "q" to q),
        )
        val items = query.page(page.page - 1, page.size).list().map { AdminMappers.user(it, school.name) }
        return Page(items, page.page, page.size, total)
    }

    private fun requireSchool(id: UUID) = schools.findById(id) ?: throw ApiException.notFound("Maktab", id)

    companion object {
        /** `s` — schools taxallusi. */
        const val ATTENDANCE_30D_SQL = """(select case when count(*) = 0 then null
                else 100.0 * count(*) filter (where a.status in ('PRESENT', 'EXCUSED')) / count(*) end
              from attendance a where a.school_id = s.id and a.attended_on >= current_date - 30)"""

        const val SCORE_30D_SQL = """(select sum(g.value / g.max_value * 100 * case g.type when 'EXAM' then 3 when 'QUIZ' then 2 else 1 end)
                / nullif(sum(case g.type when 'EXAM' then 3 when 'QUIZ' then 2 else 1 end), 0)
              from grades g where g.school_id = s.id and g.graded_on >= current_date - 30)"""
    }
}
