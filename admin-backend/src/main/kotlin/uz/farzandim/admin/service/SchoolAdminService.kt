package uz.farzandim.admin.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import uz.farzandim.admin.dto.AdminCredentialsDto
import uz.farzandim.admin.dto.CreateAdminBody
import uz.farzandim.admin.dto.CreateNoteBody
import uz.farzandim.admin.dto.CreateSchoolBody
import uz.farzandim.admin.dto.CreateSchoolResultDto
import uz.farzandim.admin.dto.CreateSubscriptionBody
import uz.farzandim.admin.dto.CredentialsDto
import uz.farzandim.admin.dto.NewAdminBody
import uz.farzandim.admin.dto.OnboardingStepDto
import uz.farzandim.admin.dto.PlatformUserDto
import uz.farzandim.admin.dto.SchoolDetailDto
import uz.farzandim.admin.dto.SchoolFlagDto
import uz.farzandim.admin.dto.SchoolNoteDto
import uz.farzandim.admin.dto.SchoolRowDto
import uz.farzandim.admin.dto.UpdateSchoolBody
import uz.farzandim.shared.ai.AiUsageService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.Page
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.School
import uz.farzandim.shared.domain.SchoolAdminLevel
import uz.farzandim.shared.domain.SchoolNote
import uz.farzandim.shared.domain.SchoolStatus
import uz.farzandim.shared.domain.SubscriptionPlanCode
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.notify.CredentialsNotifier
import uz.farzandim.shared.notify.EmailAddresses
import uz.farzandim.shared.notify.PhoneNumbers
import uz.farzandim.shared.platform.PlanAccess
import uz.farzandim.shared.repo.FeatureFlagRepository
import uz.farzandim.shared.repo.SchoolNoteRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.SchoolSubscriptionRepository
import uz.farzandim.shared.repo.SubscriptionPlanRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Passwords
import uz.farzandim.shared.security.RefreshTokenService
import uz.farzandim.shared.util.Credentials
import uz.farzandim.shared.util.Json
import uz.farzandim.shared.util.Sql
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Maktabni shartnoma asosida ulash va boshqarish.
 *
 * Maktab ish boshlashi uchun kerak bo'lgan hamma narsa bitta tranzaksiyada
 * yaratiladi: maktab + rekvizitlar + bir yoki bir nechta admin (kamida bittasi
 * superadmin) + birinchi obuna. Biror qadam yiqilsa — hech narsa qolmaydi,
 * yarim yaratilgan maktab bo'lmaydi.
 */
@ApplicationScoped
class SchoolAdminService {

    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var plans: SubscriptionPlanRepository
    @Inject lateinit var subscriptions: SchoolSubscriptionRepository
    @Inject lateinit var notes: SchoolNoteRepository
    @Inject lateinit var flags: FeatureFlagRepository
    @Inject lateinit var planAccess: PlanAccess
    @Inject lateinit var aiUsage: AiUsageService
    @Inject lateinit var refreshTokens: RefreshTokenService
    @Inject lateinit var credentialsNotifier: CredentialsNotifier
    @Inject lateinit var billing: BillingService
    @Inject lateinit var auditRecorder: AuditRecorder
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var em: EntityManager

    data class SchoolFilter(
        val q: String? = null,
        val status: SchoolStatus? = null,
        val plan: SubscriptionPlanCode? = null,
        val region: String? = null,
        val expiringDays: Int? = null,
        val unpaid: Boolean = false,
        val id: UUID? = null,
    )

    /* --------------------------------- Ro'yxat ------------------------------ */

    /**
     * Har bir qatorda sanoqlar va ishga tushirish holati.
     *
     * Kichik korrelyatsiyalangan so'rovlar indekslar bo'yicha ishlaydi
     * (`*_school_idx`); maktablar soni yuzlab bo'lgunicha bu JOIN + GROUP BY
     * dan sodda va yetarlicha tez.
     */
    fun listSchools(filter: SchoolFilter, page: PageRequest): Page<SchoolRowDto> {
        val (where, params) = whereClause(filter)

        val countQuery = em.createNativeQuery("select count(*) from schools s where $where")
        params.forEach { (k, v) -> countQuery.setParameter(k, v) }
        val total = countQuery.singleResult.asLong()
        if (total == 0L) return Page.empty(page.page, page.size)

        val sort = page.orderBy(
            mapOf(
                "name" to "s.name",
                "createdAt" to "s.created_at",
                "students" to "students",
                "until" to "s.subscription_until",
                "region" to "s.region",
                "status" to "s.status",
            ),
            "s.name",
        )

        val query = em.createNativeQuery(
            """
            $ROW_SELECT
            from schools s
            where $where
            order by $sort nulls last
            limit :limit offset :offset
            """.trimIndent(),
        )
        params.forEach { (k, v) -> query.setParameter(k, v) }
        query.setParameter("limit", page.size)
        query.setParameter("offset", page.offset)

        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        return Page(rows.map { toRow(it) }, page.page, page.size, total)
    }

    fun exportCsv(filter: SchoolFilter): String {
        val rows = listSchools(filter, PageRequest.of(1, PageRequest.MAX_SIZE, "name")).items
        val header = listOf(
            "Nomi", "Qisqa nom", "Holat", "Tarif", "Obuna tugashi", "Viloyat", "Shahar",
            "O'quvchilar", "O'qituvchilar", "Guruhlar", "Ota-onalar", "Faol (30 kun)",
            "AI (30 kun)", "Qarz, so'm", "Ishga tushirish", "Qo'shilgan",
        )
        val lines = rows.map { r ->
            listOf(
                r.name, r.shortName, r.status.name, r.plan.name, r.subscriptionUntil?.toString().orEmpty(),
                r.region, r.city, r.studentCount, r.teacherCount, r.groupCount, r.parentCount,
                r.activeUsers30d, r.aiCalls30d, r.unpaidUzs, "${r.onboardingDone}/${r.onboardingTotal}",
                r.createdAt.toString().substringBefore('T'),
            ).joinToString(";") { csvCell(it.toString()) }
        }
        // BOM — Excel UTF-8 ni to'g'ri ochishi uchun; ajratgich `;` — o'zbek/rus lokalidagi Excel uchun.
        return "\uFEFF" + (listOf(header.joinToString(";") { csvCell(it) }) + lines).joinToString("\r\n")
    }

    private fun csvCell(value: String): String {
        // Formula injection: `=`, `+`, `-`, `@` bilan boshlangan katak Excel'da formula bo'lib qoladi.
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@') && value.toLongOrNull() == null) "'$value" else value
        return if (safe.any { it == ';' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun whereClause(filter: SchoolFilter): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any>()
        filter.id?.let {
            conditions += "s.id = :id"
            params["id"] = it
        }
        if (!filter.q.isNullOrBlank()) {
            conditions += """(lower(s.name) like :pattern or lower(s.short_name) like :pattern
                or lower(s.legal_name) like :pattern or lower(s.city) like :pattern or s.tin like :pattern
                or lower(coalesce(s.contract_number, '')) like :pattern)"""
            params["pattern"] = "%${filter.q.trim().lowercase()}%"
        }
        filter.status?.let {
            conditions += "s.status = :status"
            params["status"] = it.name
        }
        filter.plan?.let {
            conditions += "s.subscription_plan = :plan"
            params["plan"] = it.name
        }
        if (!filter.region.isNullOrBlank()) {
            conditions += "lower(s.region) = :region"
            params["region"] = filter.region.trim().lowercase()
        }
        filter.expiringDays?.let {
            conditions += "s.subscription_until is not null and s.subscription_until <= current_date + :days"
            params["days"] = it.coerceIn(0, 365)
        }
        if (filter.unpaid) {
            conditions += """exists (select 1 from school_subscriptions ss where ss.school_id = s.id
                and ss.payment_status = 'UNPAID' and ss.status <> 'CANCELLED')"""
        }
        return conditions.joinToString(" and ") to params
    }

    private fun toRow(row: Array<Any?>): SchoolRowDto {
        val until = Sql.date(row[5])
        return SchoolRowDto(
            id = row[0] as UUID,
            name = row[1] as String,
            shortName = row[2] as String,
            status = SchoolStatus.valueOf(row[3] as String),
            statusReason = row[4] as String?,
            plan = SubscriptionPlanCode.valueOf(row[6] as String),
            subscriptionUntil = until,
            daysLeft = until?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) },
            region = row[7] as String,
            city = row[8] as String,
            createdAt = Sql.requireInstant(row[9]),
            studentCount = row[10].asLong(),
            teacherCount = row[11].asLong(),
            groupCount = row[12].asLong(),
            parentCount = row[13].asLong(),
            activeUsers30d = row[14].asLong(),
            aiCalls30d = row[15].asLong(),
            unpaidUzs = row[16].asLong(),
            onboardingDone = row[17].asLong().toInt(),
            onboardingTotal = OnboardingSteps.ALL.size,
        )
    }

    /* -------------------------------- Kartochka ----------------------------- */

    fun getSchool(id: UUID): SchoolDetailDto {
        val school = schools.findById(id) ?: throw ApiException.notFound("Maktab", id)
        // Ilgari ro'yxatning birinchi 200 tasidan qidirilardi — 200+ maktabda kartochka 404 berardi.
        val row = listSchools(SchoolFilter(id = id), PageRequest.of(1, 1, null)).items.firstOrNull()
            ?: throw ApiException.notFound("Maktab", id)

        return SchoolDetailDto(
            school = row,
            legalName = school.legalName,
            tin = school.tin,
            directorName = school.directorName,
            address = school.address,
            phone = school.phone,
            email = school.email,
            contactPerson = school.contactPerson,
            contactPhone = school.contactPhone,
            contractNumber = school.contractNumber,
            contractSignedOn = school.contractSignedOn,
            logoUrl = school.logoUrl,
            academicYear = school.academicYear,
            gradingSystem = school.gradingSystem,
            locale = school.locale,
            timezone = school.timezone,
            aiDailyLimit = school.aiDailyLimit,
            aiCallsToday = aiUsage.callsToday(id).toLong(),
            settings = Json.read(school.settings, emptyMap()),
            planFeatures = planAccess.features(school),
            flags = schoolFlags(id),
            admins = listAdmins(id),
            subscriptions = billing.listForSchool(id),
            onboarding = onboarding(id),
            updatedAt = school.updatedAt,
        )
    }

    private fun schoolFlags(schoolId: UUID): List<SchoolFlagDto> = flags.listAll().sortedBy { it.key }.map { flag ->
        val override = Json.obj(flag.schoolOverrides).get(schoolId.toString())?.takeIf { it.isBoolean }?.booleanValue()
        SchoolFlagDto(flag.key, flag.description, flag.enabled, override, override ?: flag.enabled)
    }

    fun onboarding(schoolId: UUID): List<OnboardingStepDto> {
        @Suppress("UNCHECKED_CAST")
        val row = em.createNativeQuery(
            """
            select
              (select count(*) from users u where u.school_id = :s and u.role = 'SCHOOL_ADMIN' and u.last_login_at is not null),
              (select count(*) from subjects x where x.school_id = :s and not x.archived),
              (select count(*) from groups g where g.school_id = :s and not g.archived),
              (select count(*) from teachers t where t.school_id = :s),
              (select count(*) from students st where st.school_id = :s and st.status = 'ACTIVE'),
              (select count(*) from schedule_slots sl where sl.school_id = :s),
              (select count(*) from parents p where p.school_id = :s),
              (select count(*) from lessons l where l.school_id = :s and l.status = 'DONE'),
              (select count(*) from parents p join users u on u.id = p.user_id
                 where p.school_id = :s and u.last_login_at is not null)
            """.trimIndent(),
        ).setParameter("s", schoolId).singleResult as Array<Any?>

        return OnboardingSteps.ALL.mapIndexed { index, (key, title) ->
            val count = row[index].asLong()
            OnboardingStepDto(key, title, count > 0, count)
        }
    }

    /* ------------------------------ Yangi maktab ---------------------------- */

    @Transactional
    fun createSchool(body: CreateSchoolBody): CreateSchoolResultDto {
        val shortName = body.shortName.trim()
        schools.findByShortName(shortName)?.let {
            throw ApiException.conflict("«$shortName» qisqa nomi band")
        }
        val tin = body.tin?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        if (tin != null) {
            if (tin.length != 9) throw ApiException.validation("STIR 9 xonali bo'lishi kerak", mapOf("tin" to "9 raqam"))
            val taken = em.createNativeQuery("select count(*) from schools where tin = :tin")
                .setParameter("tin", tin).singleResult.asLong()
            if (taken > 0) throw ApiException.conflict("Bu STIR bilan maktab allaqachon ro'yxatdan o'tgan")
        }

        val admins = normalizeAdmins(body)
        // Telefon/email bandligini maktab yaratilishidan oldin aniqlaymiz — aniq xabar bilan.
        admins.forEach { assertContactsFree(it.phone, it.email) }

        val plan = plans.findById(body.plan.name)
            ?: throw ApiException.validation("Tarif topilmadi: ${body.plan}")
        if (!plan.active) throw ApiException.validation("«${plan.code}» tarifi hozir sotuvda emas")

        val today = LocalDate.now()
        val school = School().apply {
            this.name = body.name.trim()
            this.shortName = shortName
            this.legalName = body.legalName.trim()
            this.tin = tin
            this.directorName = body.directorName.trim()
            this.region = body.region.trim()
            this.city = body.city.trim()
            this.address = body.address.trim()
            this.phone = body.phone.trim()
            this.email = body.email.trim().lowercase()
            this.contactPerson = body.contactPerson.trim()
            this.contactPhone = body.contactPhone.trim()
            this.contractNumber = body.contractNumber?.trim()?.takeIf { it.isNotEmpty() }
            this.contractSignedOn = body.contractSignedOn
            this.academicYear = body.academicYear.trim().ifBlank { defaultAcademicYear(today) }
            this.gradingSystem = body.gradingSystem
            this.locale = body.locale
            this.timezone = body.timezone
            this.subscriptionPlan = body.plan
            this.aiDailyLimit = plan.aiDailyLimit
            this.settings = Json.write(defaultSettings(planAccess.hasFeatureInPlan(plan.features, "ai")))
        }
        schools.persist(school)

        val credentials = admins.map { input -> createAdmin(school, input) }

        billing.createSubscription(
            school.id,
            CreateSubscriptionBody(
                planCode = body.plan,
                months = body.subscriptionMonths,
                startsOn = body.subscriptionStartsOn ?: today,
                studentCount = body.contractStudents,
                discountPercent = body.discountPercent,
                paymentStatus = body.paymentStatus,
                note = body.contractNumber?.let { "Shartnoma № $it" },
            ),
            audit = false,
        )

        auditRecorder.record(
            "SCHOOL_CREATED", "SCHOOL", school.id.toString(), school.id,
            mapOf("name" to school.name, "plan" to body.plan.name, "admins" to credentials.size),
        )

        em.flush()
        val first = credentials.first()
        return CreateSchoolResultDto(
            school = getSchool(school.id),
            adminCredentials = CredentialsDto(first.username, first.password, first.userId),
            credentials = credentials,
        )
    }

    private fun normalizeAdmins(body: CreateSchoolBody): List<NewAdminBody> {
        val list = body.admins.ifEmpty {
            if (body.adminFirstName.isBlank() && body.adminLastName.isBlank()) {
                emptyList()
            } else {
                listOf(
                    NewAdminBody(
                        body.adminFirstName, body.adminLastName, body.adminPhone, body.adminEmail,
                        SchoolAdminLevel.SUPER,
                    ),
                )
            }
        }
        if (list.isEmpty()) {
            throw ApiException.validation(
                "Maktab ish boshlashi uchun kamida bitta admin kerak",
                mapOf("admins" to "kamida bitta"),
            )
        }
        if (list.size > 20) throw ApiException.validation("Bir vaqtda ko'pi bilan 20 ta admin", mapOf("admins" to "20 tagacha"))

        // Takror telefon/email bitta so'rov ichida — bazaga borguncha ushlaymiz.
        val phones = list.mapNotNull { it.phone?.takeIf(String::isNotBlank)?.let(PhoneNumbers::e164) }
        if (phones.size != phones.toSet().size) throw ApiException.conflict("Adminlar ro'yxatida bir xil telefon raqami takrorlangan")
        val emails = list.mapNotNull { it.email?.takeIf(String::isNotBlank)?.lowercase() }
        if (emails.size != emails.toSet().size) throw ApiException.conflict("Adminlar ro'yxatida bir xil email takrorlangan")

        return if (list.none { it.level == SchoolAdminLevel.SUPER }) {
            listOf(list.first().copy(level = SchoolAdminLevel.SUPER)) + list.drop(1)
        } else {
            list
        }
    }

    private fun assertContactsFree(phone: String?, email: String?) {
        phone?.takeIf { it.isNotBlank() }?.let { raw ->
            val normalized = PhoneNumbers.e164(raw)
            if (users.count("phone", normalized) > 0) {
                throw ApiException.conflict("Bu telefon raqami allaqachon ro'yxatdan o'tgan: ${PhoneNumbers.mask(normalized)}")
            }
        }
        email?.takeIf { it.isNotBlank() }?.let { raw ->
            if (!EmailAddresses.isValid(raw)) throw ApiException.validation("Email noto'g'ri", mapOf("email" to raw))
            if (users.count("lower(email) = ?1", raw.trim().lowercase()) > 0) {
                throw ApiException.conflict("Bu email allaqachon ro'yxatdan o'tgan: ${EmailAddresses.mask(raw)}")
            }
        }
    }

    private fun defaultAcademicYear(today: LocalDate): String {
        // O'quv yili sentyabrda boshlanadi: 2026-yil mart — "2025-2026".
        val start = if (today.monthValue >= 8) today.year else today.year - 1
        return "$start-${start + 1}"
    }

    private fun defaultSettings(aiInPlan: Boolean): Map<String, Any?> = mapOf(
        "notifyParentsOnGrade" to true,
        "notifyParentsOnHomework" to true,
        "notifyParentsOnAbsence" to true,
        "aiAssistantEnabled" to aiInPlan,
        "autoLessonSummary" to false,
        "weeklyReportDay" to 6,
        "lessonDurationMinutes" to 45,
        "breakMinutes" to 10,
        "dayStart" to "08:30",
    )

    /* -------------------------------- Tahrirlash ---------------------------- */

    @Transactional
    fun updateSchool(id: UUID, body: UpdateSchoolBody): SchoolDetailDto {
        val school = schools.findById(id) ?: throw ApiException.notFound("Maktab", id)
        val changed = mutableListOf<String>()
        fun <T> apply(field: String, value: T?, setter: (T) -> Unit) {
            if (value != null) {
                setter(value)
                changed += field
            }
        }

        body.shortName?.trim()?.let { newShort ->
            if (!newShort.equals(school.shortName, ignoreCase = true)) {
                schools.findByShortName(newShort)?.let { throw ApiException.conflict("«$newShort» qisqa nomi band") }
            }
        }
        body.tin?.let { raw ->
            val tin = raw.filter { it.isDigit() }
            if (tin.isNotEmpty() && tin.length != 9) {
                throw ApiException.validation("STIR 9 xonali bo'lishi kerak", mapOf("tin" to "9 raqam"))
            }
        }

        apply("name", body.name?.trim()?.takeIf { it.isNotEmpty() }) { school.name = it }
        apply("shortName", body.shortName?.trim()) { school.shortName = it }
        apply("logoUrl", body.logoUrl) { school.logoUrl = it.trim().ifBlank { null } }
        apply("legalName", body.legalName) { school.legalName = it.trim() }
        apply("tin", body.tin) { school.tin = it.filter(Char::isDigit).ifBlank { null } }
        apply("directorName", body.directorName) { school.directorName = it.trim() }
        apply("region", body.region) { school.region = it.trim() }
        apply("city", body.city) { school.city = it.trim() }
        apply("address", body.address) { school.address = it.trim() }
        apply("phone", body.phone) { school.phone = it.trim() }
        apply("email", body.email) { school.email = it.trim().lowercase() }
        apply("contactPerson", body.contactPerson) { school.contactPerson = it.trim() }
        apply("contactPhone", body.contactPhone) { school.contactPhone = it.trim() }
        apply("contractNumber", body.contractNumber) { school.contractNumber = it.trim().ifBlank { null } }
        apply("contractSignedOn", body.contractSignedOn) { school.contractSignedOn = it }
        apply("academicYear", body.academicYear?.trim()?.takeIf { it.isNotEmpty() }) { school.academicYear = it }
        apply("gradingSystem", body.gradingSystem) { school.gradingSystem = it }
        apply("locale", body.locale) { school.locale = it }
        apply("timezone", body.timezone) { school.timezone = it }
        apply("aiDailyLimit", body.aiDailyLimit) { school.aiDailyLimit = it }
        body.settings?.let { patch ->
            val merged = Json.obj(school.settings)
            patch.forEach { (key, value) -> merged.set<com.fasterxml.jackson.databind.JsonNode>(key, Json.mapper.valueToTree(value)) }
            school.settings = Json.write(merged)
            changed += "settings"
        }

        school.touch()
        auditRecorder.record("SCHOOL_UPDATED", "SCHOOL", id.toString(), id, mapOf("fields" to changed))
        em.flush()
        return getSchool(id)
    }

    /**
     * Maktabni to'xtatish/arxivlash: yangi kirishlar yopiladi va barcha faol
     * sessiyalar bekor qilinadi — aks holda access token tugagunicha
     * (15 daqiqa) ishlash davom etardi.
     */
    @Transactional
    fun setSchoolStatus(id: UUID, status: SchoolStatus, reason: String?): SchoolDetailDto {
        val school = schools.findById(id) ?: throw ApiException.notFound("Maktab", id)
        val previous = school.status
        school.status = status
        school.statusReason = if (status == SchoolStatus.ACTIVE) null else reason?.trim()?.takeIf { it.isNotEmpty() }
        school.touch()

        var revoked = 0
        if (status != SchoolStatus.ACTIVE) {
            users.list("schoolId", id).forEach { revoked += refreshTokens.revokeAllForUser(it.id, "SCHOOL_$status") }
        }
        auditRecorder.record(
            "SCHOOL_STATUS", "SCHOOL", id.toString(), id,
            mapOf("from" to previous.name, "to" to status.name, "reason" to reason, "sessionsRevoked" to revoked),
        )
        em.flush()
        return getSchool(id)
    }

    /* ------------------------------- Adminlar ------------------------------- */

    fun listAdmins(schoolId: UUID): List<PlatformUserDto> {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        return users.listBySchoolAndRole(schoolId, Role.SCHOOL_ADMIN)
            .sortedWith(compareBy({ it.isSchoolSuperAdmin.not() }, { it.createdAt }))
            .map { AdminMappers.user(it, school.name) }
    }

    @Transactional
    fun createAdminAccount(schoolId: UUID, body: CreateAdminBody): CredentialsDto {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        assertContactsFree(body.phone, body.email)
        val created = createAdmin(school, NewAdminBody(body.firstName, body.lastName, body.phone, body.email, body.level))
        return CredentialsDto(created.username, created.password, created.userId)
    }

    private fun createAdmin(school: School, body: NewAdminBody): AdminCredentialsDto {
        val username = Credentials.suggestUsername(
            body.firstName.ifBlank { "admin" },
            body.lastName.ifBlank { school.shortName },
        ) { users.usernameTaken(it) }
        val password = Credentials.randomPassword(12)

        val user = UserAccount().apply {
            this.schoolId = school.id
            this.role = Role.SCHOOL_ADMIN
            this.adminLevel = body.level
            this.firstName = body.firstName.trim().ifBlank { "Admin" }
            this.lastName = body.lastName.trim()
            this.username = username
            this.phone = body.phone?.takeIf { it.isNotBlank() }?.let(PhoneNumbers::e164)
            this.email = body.email?.takeIf { it.isNotBlank() }?.trim()?.lowercase()
            this.locale = school.locale
            this.passwordHash = Passwords.hash(password)
            this.status = UserStatus.INVITED
            this.mustChangePassword = true
        }
        users.persist(user)

        credentialsNotifier.send(user.phone, username, password, school.id, user.id, user.locale, user.email)
        auditRecorder.record(
            "SCHOOL_ADMIN_CREATED", "USER", user.id.toString(), school.id,
            mapOf("level" to body.level.name, "username" to username),
        )
        return AdminCredentialsDto(user.id, user.fullName, body.level, username, password)
    }

    /* -------------------------------- Qaydlar ------------------------------- */

    fun listNotes(schoolId: UUID): List<SchoolNoteDto> {
        schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        val list = notes.listForSchool(schoolId, 200)
        val authors = users.findAllByIds(list.mapNotNull { it.authorId }.toSet()).associateBy { it.id }
        return list.map { toNoteDto(it, authors[it.authorId]) }
    }

    @Transactional
    fun addNote(schoolId: UUID, body: CreateNoteBody): SchoolNoteDto {
        schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        val note = SchoolNote().apply {
            this.schoolId = schoolId
            this.authorId = currentUser.userIdOrNull
            this.kind = body.kind
            this.body = body.body.trim()
        }
        notes.persist(note)
        auditRecorder.record("SCHOOL_NOTE_ADDED", "SCHOOL_NOTE", note.id.toString(), schoolId, mapOf("kind" to body.kind.name))
        return toNoteDto(note, currentUser.userIdOrNull?.let { users.findById(it) })
    }

    /** Qaydni faqat muallifi o'chiradi — tarix boshqalar uchun o'zgarmasin. */
    @Transactional
    fun deleteNote(noteId: UUID) {
        val note = notes.findById(noteId) ?: throw ApiException.notFound("Qayd", noteId)
        if (note.authorId != null && note.authorId != currentUser.userIdOrNull) {
            throw ApiException.forbidden("Faqat o'zingiz yozgan qaydni o'chira olasiz")
        }
        notes.delete(note)
        auditRecorder.record("SCHOOL_NOTE_DELETED", "SCHOOL_NOTE", noteId.toString(), note.schoolId)
    }

    private fun toNoteDto(note: SchoolNote, author: UserAccount?) = SchoolNoteDto(
        id = note.id,
        schoolId = note.schoolId,
        kind = note.kind,
        body = note.body,
        authorId = note.authorId,
        authorName = author?.let { "${it.firstName} ${it.lastName}".trim() },
        createdAt = note.createdAt,
    )

    companion object {
        private val ROW_SELECT = """
            select s.id, s.name, s.short_name, s.status, s.status_reason, s.subscription_until,
                   s.subscription_plan, s.region, s.city, s.created_at,
                   (select count(*) from students st where st.school_id = s.id and st.status = 'ACTIVE') as students,
                   (select count(*) from teachers t where t.school_id = s.id) as teachers,
                   (select count(*) from groups g where g.school_id = s.id and not g.archived) as groups_count,
                   (select count(*) from parents p where p.school_id = s.id) as parents,
                   (select count(*) from users u where u.school_id = s.id
                        and u.last_login_at >= now() - interval '30 days') as active_users,
                   (select coalesce(sum(a.calls), 0) from ai_usage_daily a where a.school_id = s.id
                        and a.usage_date >= current_date - 30) as ai_calls,
                   (select coalesce(sum(ss.amount_uzs), 0) from school_subscriptions ss where ss.school_id = s.id
                        and ss.payment_status = 'UNPAID' and ss.status <> 'CANCELLED') as unpaid,
                   ${OnboardingSteps.DONE_SQL} as onboarding_done
        """.trimIndent()
    }
}

/**
 * Maktabni ishga tushirish bosqichlari — docs/00-product-overview.md,
 * "Maktabni ishga tushirish" oqimi. Ro'yxat va kartochka bir xil tartibda.
 */
object OnboardingSteps {
    val ALL: List<Pair<String, String>> = listOf(
        "ADMIN_LOGIN" to "Admin tizimga kirdi",
        "SUBJECTS" to "Fanlar kiritildi",
        "GROUPS" to "Guruhlar yaratildi",
        "TEACHERS" to "O'qituvchilar qo'shildi",
        "STUDENTS" to "O'quvchilar qo'shildi",
        "SCHEDULE" to "Dars jadvali tuzildi",
        "PARENTS" to "Ota-onalar biriktirildi",
        "FIRST_LESSON" to "Birinchi dars o'tkazildi",
        "PARENT_APP" to "Ota-onalar ilovaga kirdi",
    )

    /** `s` — schools jadvali taxallusi. Har bir bosqich 0 yoki 1. */
    val DONE_SQL = """(
        (case when exists (select 1 from users u where u.school_id = s.id and u.role = 'SCHOOL_ADMIN' and u.last_login_at is not null) then 1 else 0 end)
      + (case when exists (select 1 from subjects x where x.school_id = s.id and not x.archived) then 1 else 0 end)
      + (case when exists (select 1 from groups g where g.school_id = s.id and not g.archived) then 1 else 0 end)
      + (case when exists (select 1 from teachers t where t.school_id = s.id) then 1 else 0 end)
      + (case when exists (select 1 from students st where st.school_id = s.id and st.status = 'ACTIVE') then 1 else 0 end)
      + (case when exists (select 1 from schedule_slots sl where sl.school_id = s.id) then 1 else 0 end)
      + (case when exists (select 1 from parents p where p.school_id = s.id) then 1 else 0 end)
      + (case when exists (select 1 from lessons l where l.school_id = s.id and l.status = 'DONE') then 1 else 0 end)
      + (case when exists (select 1 from parents p join users u on u.id = p.user_id
                           where p.school_id = s.id and u.last_login_at is not null) then 1 else 0 end)
    )"""
}
