package uz.farzandim.admin.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import uz.farzandim.admin.dto.BillingMonthDto
import uz.farzandim.admin.dto.BillingSummaryDto
import uz.farzandim.admin.dto.CreateSubscriptionBody
import uz.farzandim.admin.dto.MarkPaidBody
import uz.farzandim.admin.dto.PlanDto
import uz.farzandim.admin.dto.SubscriptionDto
import uz.farzandim.admin.dto.UpdatePlanBody
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.Page
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.PaymentStatus
import uz.farzandim.shared.domain.SchoolSubscription
import uz.farzandim.shared.domain.SubscriptionPlanCode
import uz.farzandim.shared.domain.SubscriptionStatus
import uz.farzandim.shared.platform.PlanFeatures
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.SchoolSubscriptionRepository
import uz.farzandim.shared.repo.SubscriptionPlanRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.util.Json
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Obuna va to'lov.
 *
 * Obuna muddati va to'lov — alohida narsalar: maktab shartnoma imzolangan
 * kundan ishlaydi, pul esa keyinroq (bank o'tkazmasi) kelishi mumkin.
 * Qarz panelda ko'rinadi, lekin maktabni avtomatik to'xtatmaydi — bu qaror
 * inson tomonidan qabul qilinadi.
 */
@ApplicationScoped
class BillingService {

    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var plans: SubscriptionPlanRepository
    @Inject lateinit var subscriptions: SchoolSubscriptionRepository
    @Inject lateinit var auditRecorder: AuditRecorder
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var em: EntityManager

    fun listForSchool(schoolId: UUID): List<SubscriptionDto> {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        return subscriptions.listForSchool(schoolId).map { AdminMappers.subscription(it, school.name) }
    }

    /**
     * Yangi obuna yoki uzaytirish.
     *
     * Boshlanish sanasi berilmasa va maktabda hali tugamagan obuna bo'lsa —
     * yangisi o'sha tugagan kundan boshlanadi (uzaytirish kunlarni yo'qotmaydi).
     */
    @Transactional
    fun createSubscription(schoolId: UUID, body: CreateSubscriptionBody, audit: Boolean = true): SubscriptionDto {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        val plan = plans.findById(body.planCode.name)
            ?: throw ApiException.validation("Tarif topilmadi: ${body.planCode}")
        if (!plan.active) throw ApiException.validation("«${plan.code}» tarifi hozir sotuvda emas")

        val today = LocalDate.now()
        val startsOn = body.startsOn ?: school.subscriptionUntil?.takeIf { !it.isBefore(today) }?.plusDays(1) ?: today
        val endsOn = startsOn.plusMonths(body.months.toLong())

        val activeStudents = em.createNativeQuery(
            "select count(*) from students where school_id = :s and status = 'ACTIVE'",
        ).setParameter("s", schoolId).singleResult.asLong().toInt()
        val studentCount = body.studentCount ?: activeStudents

        val base = maxOf(
            plan.minMonthlyUzs * body.months,
            plan.pricePerStudentUzs * studentCount * body.months,
        )
        val amount = body.amountUzs ?: (base * (100 - body.discountPercent) / 100)

        val subscription = SchoolSubscription().apply {
            this.schoolId = schoolId
            this.planCode = plan.code
            this.startsOn = startsOn
            this.endsOn = endsOn
            this.studentCount = studentCount
            this.amountUzs = amount
            this.discountPercent = body.discountPercent
            this.status = SubscriptionStatus.ACTIVE
            this.paymentStatus = body.paymentStatus
            this.paidOn = if (body.paymentStatus == PaymentStatus.PAID) body.paidOn ?: today else null
            this.paymentMethod = body.paymentMethod?.trim()?.takeIf { it.isNotEmpty() }
            this.paymentReference = body.paymentReference?.trim()?.takeIf { it.isNotEmpty() }
            this.note = body.note?.trim()?.takeIf { it.isNotEmpty() }
            this.createdBy = currentUser.userIdOrNull
        }
        subscriptions.persist(subscription)

        // Maktab kartasi: tarif darhol, muddat — eng uzog'i.
        school.subscriptionPlan = body.planCode
        school.subscriptionUntil = listOfNotNull(school.subscriptionUntil, endsOn).max()
        school.aiDailyLimit = plan.aiDailyLimit
        school.touch()

        if (audit) {
            auditRecorder.record(
                "SUBSCRIPTION_CREATED", "SUBSCRIPTION", subscription.id.toString(), schoolId,
                mapOf("plan" to plan.code, "months" to body.months, "amountUzs" to amount, "startsOn" to startsOn.toString()),
            )
        }
        return AdminMappers.subscription(subscription, school.name)
    }

    fun list(
        schoolId: UUID?,
        paymentStatus: PaymentStatus?,
        status: SubscriptionStatus?,
        page: PageRequest,
    ): Page<SubscriptionDto> {
        val conditions = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any>()
        schoolId?.let {
            conditions += "ss.school_id = :school"
            params["school"] = it
        }
        paymentStatus?.let {
            conditions += "ss.payment_status = :pay"
            params["pay"] = it.name
        }
        status?.let {
            conditions += "$EFFECTIVE_STATUS_SQL = :status"
            params["status"] = it.name
        }
        val where = conditions.joinToString(" and ")

        val count = em.createNativeQuery("select count(*) from school_subscriptions ss where $where")
        params.forEach { (k, v) -> count.setParameter(k, v) }
        val total = count.singleResult.asLong()
        if (total == 0L) return Page.empty(page.page, page.size)

        val idsQuery = em.createNativeQuery(
            "select ss.id from school_subscriptions ss where $where order by ss.created_at desc limit :limit offset :offset",
        )
        params.forEach { (k, v) -> idsQuery.setParameter(k, v) }
        idsQuery.setParameter("limit", page.size)
        idsQuery.setParameter("offset", page.offset)

        @Suppress("UNCHECKED_CAST")
        val ids = (idsQuery.resultList as List<Any?>).map { it as UUID }
        if (ids.isEmpty()) return Page(emptyList(), page.page, page.size, total)
        val byId = subscriptions.list("id in ?1", ids).associateBy { it.id }
        val names = schools.list("id in ?1", byId.values.map { it.schoolId }.toSet()).associate { it.id to it.name }
        val items = ids.mapNotNull { byId[it] }.map { AdminMappers.subscription(it, names[it.schoolId]) }
        return Page(items, page.page, page.size, total)
    }

    @Transactional
    fun markPaid(id: UUID, body: MarkPaidBody): SubscriptionDto {
        val subscription = subscriptions.findById(id) ?: throw ApiException.notFound("Obuna", id)
        if (subscription.status == SubscriptionStatus.CANCELLED) {
            throw ApiException.validation("Bekor qilingan obuna uchun to'lov belgilanmaydi")
        }
        subscription.paymentStatus = if (body.waived) PaymentStatus.WAIVED else PaymentStatus.PAID
        subscription.paidOn = body.paidOn ?: LocalDate.now()
        subscription.paymentMethod = body.method?.trim()?.takeIf { it.isNotEmpty() }
        subscription.paymentReference = body.reference?.trim()?.takeIf { it.isNotEmpty() }

        auditRecorder.record(
            if (body.waived) "SUBSCRIPTION_WAIVED" else "SUBSCRIPTION_PAID",
            "SUBSCRIPTION", id.toString(), subscription.schoolId,
            mapOf("amountUzs" to subscription.amountUzs, "method" to subscription.paymentMethod, "paidOn" to subscription.paidOn.toString()),
        )
        return AdminMappers.subscription(subscription, schools.findById(subscription.schoolId)?.name)
    }

    /**
     * Bekor qilish. Maktabning obuna muddati qolgan obunalar bo'yicha qayta
     * hisoblanadi: birortasi qolmasa — maktab kirishi yopiladi.
     */
    @Transactional
    fun cancel(id: UUID, reason: String?): SubscriptionDto {
        val subscription = subscriptions.findById(id) ?: throw ApiException.notFound("Obuna", id)
        if (subscription.status == SubscriptionStatus.CANCELLED) return AdminMappers.subscription(subscription)

        subscription.status = SubscriptionStatus.CANCELLED
        subscription.cancelledAt = Instant.now()
        reason?.trim()?.takeIf { it.isNotEmpty() }?.let {
            subscription.note = listOfNotNull(subscription.note, "Bekor qilindi: $it").joinToString("\n")
        }

        val school = schools.findById(subscription.schoolId) ?: throw ApiException.notFound("Maktab", subscription.schoolId)
        val remaining = subscriptions.listForSchool(school.id).filter { it.id != id && it.status != SubscriptionStatus.CANCELLED }
        val latest = remaining.maxByOrNull { it.endsOn }
        school.subscriptionUntil = latest?.endsOn ?: LocalDate.now().minusDays(1)
        latest?.let { current ->
            SubscriptionPlanCode.entries.firstOrNull { it.name == current.planCode }?.let { school.subscriptionPlan = it }
            plans.findById(current.planCode)?.let { school.aiDailyLimit = it.aiDailyLimit }
        }
        school.touch()

        auditRecorder.record(
            "SUBSCRIPTION_CANCELLED", "SUBSCRIPTION", id.toString(), school.id,
            mapOf("reason" to reason, "schoolUntil" to school.subscriptionUntil.toString()),
        )
        return AdminMappers.subscription(subscription, school.name)
    }

    fun summary(months: Int): BillingSummaryDto {
        fun long(sql: String): Long = em.createNativeQuery(sql).singleResult.asLong()

        val firstMonth = YearMonth.now().minusMonths((months - 1).toLong())

        @Suppress("UNCHECKED_CAST")
        val invoiced = (
            em.createNativeQuery(
                """
                select to_char(starts_on, 'YYYY-MM'), coalesce(sum(amount_uzs), 0)
                from school_subscriptions
                where status <> 'CANCELLED' and starts_on >= :from
                group by 1
                """.trimIndent(),
            ).setParameter("from", firstMonth.atDay(1)).resultList as List<Array<Any?>>
            ).associate { it[0] as String to it[1].asLong() }

        @Suppress("UNCHECKED_CAST")
        val paid = (
            em.createNativeQuery(
                """
                select to_char(paid_on, 'YYYY-MM'), coalesce(sum(amount_uzs), 0)
                from school_subscriptions
                where payment_status = 'PAID' and paid_on >= :from
                group by 1
                """.trimIndent(),
            ).setParameter("from", firstMonth.atDay(1)).resultList as List<Array<Any?>>
            ).associate { it[0] as String to it[1].asLong() }

        val monthList = (0 until months).map { firstMonth.plusMonths(it.toLong()).toString() }

        return BillingSummaryDto(
            monthlyRecurringUzs = long(MRR_SQL),
            paidThisMonthUzs = long(
                "select coalesce(sum(amount_uzs), 0) from school_subscriptions " +
                    "where payment_status = 'PAID' and date_trunc('month', paid_on) = date_trunc('month', current_date)",
            ),
            paidThisYearUzs = long(
                "select coalesce(sum(amount_uzs), 0) from school_subscriptions " +
                    "where payment_status = 'PAID' and date_trunc('year', paid_on) = date_trunc('year', current_date)",
            ),
            unpaidUzs = long(
                "select coalesce(sum(amount_uzs), 0) from school_subscriptions where payment_status = 'UNPAID' and status <> 'CANCELLED'",
            ),
            unpaidCount = long(
                "select count(*) from school_subscriptions where payment_status = 'UNPAID' and status <> 'CANCELLED'",
            ),
            months = monthList.map { BillingMonthDto(it, invoiced[it] ?: 0, paid[it] ?: 0) },
        )
    }

    /* -------------------------------- Tariflar ------------------------------ */

    fun listPlans(): List<PlanDto> {
        @Suppress("UNCHECKED_CAST")
        val counts = (
            em.createNativeQuery("select subscription_plan, count(*) from schools where status <> 'ARCHIVED' group by 1")
                .resultList as List<Array<Any?>>
            ).associate { it[0] as String to it[1].asLong() }

        return plans.listAll().sortedBy { SubscriptionPlanCode.valueOf(it.code).ordinal }.map {
            PlanDto(
                code = it.code,
                title = it.title,
                pricePerStudentUzs = it.pricePerStudentUzs,
                minMonthlyUzs = it.minMonthlyUzs,
                maxStudents = it.maxStudents,
                aiDailyLimit = it.aiDailyLimit,
                features = Json.read(it.features, emptyMap()),
                active = it.active,
                schools = counts[it.code] ?: 0,
            )
        }
    }

    @Transactional
    fun updatePlan(code: String, body: UpdatePlanBody): PlanDto {
        val plan = plans.findById(code) ?: throw ApiException.notFound("Tarif", code)
        body.title?.trim()?.takeIf { it.isNotEmpty() }?.let { plan.title = it }
        body.pricePerStudentUzs?.let { plan.pricePerStudentUzs = it }
        body.minMonthlyUzs?.let { plan.minMonthlyUzs = it }
        body.maxStudents?.let { plan.maxStudents = it.takeIf { max -> max > 0 } }
        body.aiDailyLimit?.let { plan.aiDailyLimit = it }
        body.active?.let { plan.active = it }
        body.features?.let { patch ->
            val unknown = patch.keys - PlanFeatures.ALL.toSet()
            if (unknown.isNotEmpty()) {
                throw ApiException.validation("Noma'lum imkoniyat: ${unknown.joinToString()}", mapOf("features" to PlanFeatures.ALL.joinToString()))
            }
            val merged = Json.obj(plan.features)
            patch.forEach { (key, value) -> merged.put(key, value) }
            plan.features = Json.write(merged)
        }
        plan.updatedAt = Instant.now()

        var schoolsUpdated = 0
        if (body.applyAiLimitToSchools) {
            schoolsUpdated = em.createNativeQuery(
                "update schools set ai_daily_limit = :limit, updated_at = now() where subscription_plan = :code",
            ).setParameter("limit", plan.aiDailyLimit).setParameter("code", code).executeUpdate()
        }

        auditRecorder.record(
            "PLAN_UPDATED", "PLAN", code, null,
            mapOf(
                "price" to plan.pricePerStudentUzs,
                "minMonthly" to plan.minMonthlyUzs,
                "aiDailyLimit" to plan.aiDailyLimit,
                "features" to body.features,
                "schoolsUpdated" to schoolsUpdated,
            ),
        )
        em.flush()
        return listPlans().first { it.code == code }
    }

    companion object {
        /** Bazadagi holat + sana → samarali holat (AdminMappers.effectiveStatus bilan bir xil). */
        const val EFFECTIVE_STATUS_SQL = """(case when ss.status <> 'ACTIVE' then ss.status
            when ss.ends_on < current_date then 'EXPIRED'
            when ss.starts_on > current_date then 'PENDING'
            else 'ACTIVE' end)"""

        /** Oylik takroriy daromad: bugun amalda bo'lgan obunalarning oylik ulushi. */
        const val MRR_SQL = """
            select coalesce(sum(amount_uzs / greatest(1, (extract(year from age(ends_on, starts_on)) * 12
                   + extract(month from age(ends_on, starts_on)))::int)), 0)
            from school_subscriptions
            where status = 'ACTIVE' and starts_on <= current_date and ends_on >= current_date"""
    }
}
