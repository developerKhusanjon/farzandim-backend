package uz.farzandim.admin.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import uz.farzandim.admin.dto.AiBucketDto
import uz.farzandim.admin.dto.AiSummaryDto
import uz.farzandim.admin.dto.AiUsageRowDto
import uz.farzandim.admin.dto.AnnouncementBody
import uz.farzandim.admin.dto.AnnouncementResultDto
import uz.farzandim.admin.dto.AuditRowDto
import uz.farzandim.admin.dto.DeliveryStatusDto
import uz.farzandim.admin.dto.EmailRowDto
import uz.farzandim.admin.dto.FeatureFlagDto
import uz.farzandim.admin.dto.FlagOverrideDto
import uz.farzandim.admin.dto.JobRowDto
import uz.farzandim.admin.dto.SmsRowDto
import uz.farzandim.admin.dto.UpdateFlagBody
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.Page
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.JobStatus
import uz.farzandim.shared.domain.MessageCategory
import uz.farzandim.shared.domain.MessagePurpose
import uz.farzandim.shared.notify.EmailAddresses
import uz.farzandim.shared.notify.EmailRequest
import uz.farzandim.shared.notify.EmailResult
import uz.farzandim.shared.notify.EmailService
import uz.farzandim.shared.notify.EmailSettings
import uz.farzandim.shared.notify.NotificationService
import uz.farzandim.shared.notify.PhoneNumbers
import uz.farzandim.shared.notify.PushMessage
import uz.farzandim.shared.notify.PushService
import uz.farzandim.shared.notify.PushSettings
import uz.farzandim.shared.notify.SmsRequest
import uz.farzandim.shared.notify.SmsResult
import uz.farzandim.shared.notify.SmsService
import uz.farzandim.shared.notify.SmsSettings
import uz.farzandim.shared.repo.AiJobRepository
import uz.farzandim.shared.repo.DeviceTokenRepository
import uz.farzandim.shared.repo.EmailRepository
import uz.farzandim.shared.repo.FeatureFlagRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.SmsRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.util.Json
import uz.farzandim.shared.util.Sql
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Platforma monitoringi: AI sarfi, joblar, aloqa kanallari, bayroqlar,
 * e'lonlar va audit.
 *
 * Maktab ichidagi CRM ma'lumotini bu servis o'zgartirmaydi.
 */
@ApplicationScoped
class PlatformService {

    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var flags: FeatureFlagRepository
    @Inject lateinit var jobs: AiJobRepository
    @Inject lateinit var smsService: SmsService
    @Inject lateinit var smsSettings: SmsSettings
    @Inject lateinit var smsRepository: SmsRepository
    @Inject lateinit var pushService: PushService
    @Inject lateinit var pushSettings: PushSettings
    @Inject lateinit var deviceTokens: DeviceTokenRepository
    @Inject lateinit var emailService: EmailService
    @Inject lateinit var emailSettings: EmailSettings
    @Inject lateinit var emailRepository: EmailRepository
    @Inject lateinit var notifications: NotificationService
    @Inject lateinit var auditRecorder: AuditRecorder
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var em: EntityManager

    data class AuditFilter(
        val schoolId: UUID? = null,
        val action: String? = null,
        val actorId: UUID? = null,
        val entityType: String? = null,
        val from: LocalDate? = null,
        val to: LocalDate? = null,
    )

    /* ----------------------------------- AI --------------------------------- */

    fun aiUsage(schoolId: UUID?, days: Int): List<AiUsageRowDto> {
        val filter = if (schoolId != null) "and a.school_id = :school" else ""
        val query = em.createNativeQuery(
            """
            select a.school_id, s.name, a.usage_date, a.feature, a.calls,
                   a.input_tokens, a.output_tokens, a.cost_micros
            from ai_usage_daily a join schools s on s.id = a.school_id
            where a.usage_date >= current_date - make_interval(days => :days) $filter
            order by a.usage_date desc, a.calls desc
            limit 500
            """.trimIndent(),
        ).setParameter("days", days)
        if (schoolId != null) query.setParameter("school", schoolId)

        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        return rows.map {
            AiUsageRowDto(
                schoolId = it[0] as UUID,
                schoolName = it[1] as String,
                usageDate = Sql.requireDate(it[2]),
                feature = it[3] as String,
                calls = (it[4] as Number).toInt(),
                inputTokens = it[5].asLong(),
                outputTokens = it[6].asLong(),
                costUzs = it[7].asLong() / 1_000_000,
            )
        }
    }

    /** Guruhlangan sarf — xom qatorlar 500 bilan cheklangan, bu esa to'liq yig'indi. */
    fun aiSummary(schoolId: UUID?, days: Int): AiSummaryDto {
        val filter = if (schoolId != null) "and a.school_id = :school" else ""

        fun buckets(keyExpr: String, labelExpr: String, join: String = "", order: String = "3 desc"): List<AiBucketDto> {
            val query = em.createNativeQuery(
                """
                select $keyExpr, $labelExpr, coalesce(sum(a.calls), 0), coalesce(sum(a.cost_micros), 0) / 1000000,
                       coalesce(sum(a.input_tokens + a.output_tokens), 0)
                from ai_usage_daily a $join
                where a.usage_date >= current_date - make_interval(days => :days) $filter
                group by 1, 2
                order by $order
                """.trimIndent(),
            ).setParameter("days", days)
            if (schoolId != null) query.setParameter("school", schoolId)

            @Suppress("UNCHECKED_CAST")
            val rows = query.resultList as List<Array<Any?>>
            return rows.map { AiBucketDto(it[0].toString(), it[1].toString(), it[2].asLong(), it[3].asLong(), it[4].asLong()) }
        }

        val byFeature = buckets("a.feature", "a.feature")
        val bySchool = buckets("a.school_id::text", "s.name", "join schools s on s.id = a.school_id")
        val byDay = buckets("to_char(a.usage_date, 'YYYY-MM-DD')", "to_char(a.usage_date, 'DD.MM')", order = "1")

        return AiSummaryDto(
            days = days,
            totalCalls = byFeature.sumOf { it.calls },
            totalCostUzs = byFeature.sumOf { it.costUzs },
            totalTokens = byFeature.sumOf { it.tokens },
            byFeature = byFeature,
            bySchool = bySchool,
            byDay = byDay,
        )
    }

    /* ---------------------------- Job monitoringi --------------------------- */

    fun listJobs(status: JobStatus?, schoolId: UUID?, limit: Int): List<JobRowDto> {
        val conditions = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any>()
        if (status != null) {
            conditions += "j.status = :status"
            params["status"] = status.name
        }
        if (schoolId != null) {
            conditions += "j.school_id = :school"
            params["school"] = schoolId
        }
        return queryJobs(conditions.joinToString(" and "), params, limit)
    }

    private fun queryJobs(where: String, params: Map<String, Any>, limit: Int): List<JobRowDto> {
        val query = em.createNativeQuery(
            """
            select j.id, j.school_id, s.name, j.type, j.status, j.attempts, j.error_code, j.error_message,
                   j.model, j.input_tokens, j.output_tokens, j.created_at, j.finished_at
            from ai_jobs j left join schools s on s.id = j.school_id
            where $where
            order by j.created_at desc
            limit :limit
            """.trimIndent(),
        ).setParameter("limit", limit)
        params.forEach { (k, v) -> query.setParameter(k, v) }

        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        return rows.map {
            JobRowDto(
                id = it[0] as UUID,
                schoolId = it[1] as UUID?,
                schoolName = it[2] as String?,
                type = it[3] as String,
                status = JobStatus.valueOf(it[4] as String),
                attempts = (it[5] as Number).toInt(),
                errorCode = it[6] as String?,
                errorMessage = it[7] as String?,
                model = it[8] as String?,
                inputTokens = (it[9] as Number?)?.toInt(),
                outputTokens = (it[10] as Number?)?.toInt(),
                createdAt = Sql.requireInstant(it[11]),
                finishedAt = Sql.instant(it[12]),
            )
        }
    }

    /** Muvaffaqiyatsiz ishni qayta navbatga qo'yish. */
    @Transactional
    fun retryJob(jobId: UUID): JobRowDto {
        val job = jobs.findById(jobId) ?: throw ApiException.notFound("Job", jobId)
        if (job.status != JobStatus.FAILED) {
            throw ApiException.validation("Faqat FAILED holatidagi job qayta ishga tushiriladi")
        }
        job.status = JobStatus.QUEUED
        job.attempts = 0
        job.errorCode = null
        job.errorMessage = null
        job.runAfter = Instant.now()
        job.touch()
        auditRecorder.record("JOB_RETRY", "AI_JOB", jobId.toString(), job.schoolId)
        em.flush()
        return queryJobs("j.id = :id", mapOf("id" to jobId), 1).first()
    }

    /* ------------------------- SMS, email va push ------------------------- */

    fun deliveryStatus(): DeliveryStatusDto {
        fun count(sql: String): Long = em.createNativeQuery(sql).singleResult.asLong()

        val smsProvider = smsService.provider
        val pushProvider = pushService.provider
        val emailProvider = emailService.provider

        return DeliveryStatusDto(
            // `provider` sozlanmagan bo'lsa `log` ga tushadi — shuni ko'rsatamiz,
            // chunki operator "eskiz yozganman, nega ketmayapti" deb so'raydi.
            smsProvider = smsProvider.name,
            smsConfigured = smsProvider.name == smsSettings.provider().lowercase(),
            smsCredentialsEnabled = smsSettings.sendCredentials(),
            pushProvider = pushProvider.name,
            pushConfigured = pushProvider.name == pushSettings.provider().lowercase(),
            smsSentToday = count(
                "select count(*) from sms_messages where created_at >= current_date and status in ('SENT','DELIVERED')",
            ),
            smsPartsToday = count(
                "select coalesce(sum(parts), 0) from sms_messages where created_at >= current_date and status in ('SENT','DELIVERED')",
            ),
            smsFailedToday = count(
                "select count(*) from sms_messages where created_at >= current_date and status in ('FAILED','REJECTED')",
            ),
            emailProvider = emailProvider.name,
            emailConfigured = emailProvider.name == emailSettings.provider().lowercase(),
            emailSentToday = count(
                "select count(*) from email_messages where created_at >= current_date and status in ('SENT','DELIVERED')",
            ),
            emailFailedToday = count(
                "select count(*) from email_messages where created_at >= current_date and status in ('FAILED','REJECTED')",
            ),
            activeDevices = count("select count(*) from device_tokens where disabled_at is null"),
        )
    }

    fun listSms(schoolId: UUID?, limit: Int): List<SmsRowDto> {
        val query = if (schoolId != null) {
            smsRepository.find("schoolId = ?1 order by createdAt desc", schoolId)
        } else {
            smsRepository.find("order by createdAt desc")
        }
        return query.page(0, limit).list().map {
            SmsRowDto(
                id = it.id,
                schoolId = it.schoolId,
                provider = it.provider,
                // To'liq raqam admin panelida ham ko'rinmaydi
                recipient = PhoneNumbers.mask(it.recipient),
                purpose = it.purpose.name,
                parts = it.parts,
                status = it.status.name,
                errorCode = it.errorCode,
                createdAt = it.createdAt,
                sentAt = it.sentAt,
                deliveredAt = it.deliveredAt,
            )
        }
    }

    /** Shlyuzni tekshirish: haqiqiy SMS ketadi va hisobga tushadi. */
    fun sendTestSms(phone: String, text: String): SmsResult {
        val result = smsService.send(
            SmsRequest(
                recipient = phone,
                text = text,
                purpose = MessagePurpose.TEST,
                userId = currentUser.userIdOrNull,
            ),
        )
        auditRecorder.record("SMS_TEST", "SMS", PhoneNumbers.mask(phone), null, mapOf("accepted" to result.accepted))
        return result
    }

    fun listEmails(schoolId: UUID?, limit: Int): List<EmailRowDto> {
        val query = if (schoolId != null) {
            emailRepository.find("schoolId = ?1 order by createdAt desc", schoolId)
        } else {
            emailRepository.find("order by createdAt desc")
        }
        return query.page(0, limit).list().map {
            EmailRowDto(
                id = it.id,
                schoolId = it.schoolId,
                provider = it.provider,
                // To'liq manzil admin panelida ham ko'rinmaydi
                recipient = EmailAddresses.mask(it.recipient),
                subject = it.subject,
                purpose = it.purpose.name,
                status = it.status.name,
                errorCode = it.errorCode,
                createdAt = it.createdAt,
                sentAt = it.sentAt,
            )
        }
    }

    /** SMTP ulanishini tekshirish: haqiqiy xat ketadi va hisobga tushadi. */
    fun sendTestEmail(email: String, subject: String, text: String): EmailResult {
        val result = emailService.send(
            EmailRequest(
                recipient = email,
                subject = subject,
                html = "<p style=\"font-family:sans-serif\">" + escapeHtml(text) + "</p>",
                text = text,
                purpose = MessagePurpose.TEST,
                userId = currentUser.userIdOrNull,
            ),
        )
        auditRecorder.record("EMAIL_TEST", "EMAIL", EmailAddresses.mask(email), null, mapOf("accepted" to result.accepted))
        return result
    }

    /** Push'ni tekshirish: foydalanuvchining barcha faol qurilmalariga. */
    fun sendTestPush(userId: UUID): Map<String, Any> {
        val user = users.findById(userId) ?: throw ApiException.notFound("Foydalanuvchi", userId)
        val tokens = deviceTokens.listForUsers(listOf(userId))
        if (tokens.isEmpty()) {
            return mapOf("devices" to 0, "delivered" to 0, "note" to "Foydalanuvchida faol qurilma yo'q")
        }
        val delivered = pushService.send(
            tokens.map { it.token },
            PushMessage(
                title = "Farzandim AI",
                body = "Sinov bildirishnomasi — push kanali ishlayapti.",
                data = mapOf("category" to "GENERAL", "test" to "true"),
            ),
            Runnable::run,
        )
        auditRecorder.record("PUSH_TEST", "USER", userId.toString(), user.schoolId, mapOf("devices" to tokens.size))
        return mapOf("devices" to tokens.size, "delivered" to delivered)
    }

    /* ------------------------------ Bayroqlar ------------------------------ */

    fun listFlags(): List<FeatureFlagDto> {
        val all = flags.listAll().sortedBy { it.key }
        val overrides = all.associate { it.key to Json.read<Map<String, Boolean>>(it.schoolOverrides, emptyMap()) }
        val schoolIds = overrides.values.flatMap { it.keys }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        val names = if (schoolIds.isEmpty()) emptyMap() else schools.list("id in ?1", schoolIds).associate { it.id to it.name }

        return all.map { flag ->
            val map = overrides[flag.key].orEmpty()
            FeatureFlagDto(
                key = flag.key,
                description = flag.description,
                enabled = flag.enabled,
                schoolOverrides = map,
                overrides = map.mapNotNull { (id, value) ->
                    runCatching { UUID.fromString(id) }.getOrNull()?.let { FlagOverrideDto(it, names[it], value) }
                }.sortedBy { it.schoolName ?: "" },
                updatedAt = flag.updatedAt,
            )
        }
    }

    @Transactional
    fun updateFlag(key: String, body: UpdateFlagBody): FeatureFlagDto {
        val flag = flags.findById(key) ?: throw ApiException.notFound("Bayroq", key)
        body.enabled?.let { flag.enabled = it }
        if (body.schoolId != null) {
            schools.findById(body.schoolId) ?: throw ApiException.notFound("Maktab", body.schoolId)
            val overrides = Json.obj(flag.schoolOverrides)
            when {
                body.clearSchool -> overrides.remove(body.schoolId.toString())
                body.schoolEnabled != null -> overrides.put(body.schoolId.toString(), body.schoolEnabled)
            }
            flag.schoolOverrides = Json.write(overrides)
        }
        flag.updatedAt = Instant.now()
        auditRecorder.record(
            "FLAG_UPDATED", "FEATURE_FLAG", key, body.schoolId,
            mapOf("enabled" to body.enabled, "schoolEnabled" to body.schoolEnabled, "clearSchool" to body.clearSchool),
        )
        em.flush()
        return listFlags().first { it.key == key }
    }

    /* ------------------------------- E'lonlar ------------------------------ */

    /**
     * Maktab xodimlariga platforma e'loni (texnik ishlar, yangi imkoniyat).
     * CRM'dagi bildirishnomalar ro'yxatiga tushadi; qurilmasi bo'lsa — push.
     */
    @Transactional
    fun announce(body: AnnouncementBody): AnnouncementResultDto {
        val targetSchools = if (body.schoolIds.isEmpty()) {
            schools.list("status", uz.farzandim.shared.domain.SchoolStatus.ACTIVE)
        } else {
            schools.list("id in ?1", body.schoolIds.toSet())
        }
        val roles = if (body.audience == "STAFF") {
            listOf(uz.farzandim.shared.domain.Role.SCHOOL_ADMIN, uz.farzandim.shared.domain.Role.TEACHER)
        } else {
            listOf(uz.farzandim.shared.domain.Role.SCHOOL_ADMIN)
        }

        var recipients = 0
        targetSchools.forEach { school ->
            val ids = users.list(
                "schoolId = ?1 and role in ?2 and status <> ?3",
                school.id, roles, uz.farzandim.shared.domain.UserStatus.BLOCKED,
            ).map { it.id }
            if (ids.isNotEmpty()) {
                notifications.notifyUsers(
                    ids, school.id, MessageCategory.ANNOUNCEMENT, body.title.trim(), body.body.trim(),
                    payload = mapOf("source" to "platform"), colorKey = "purple", iconKey = "megaphone",
                )
                recipients += ids.size
            }
        }

        auditRecorder.record(
            "ANNOUNCEMENT_SENT", "ANNOUNCEMENT", null, targetSchools.singleOrNull()?.id,
            mapOf("title" to body.title, "audience" to body.audience, "schools" to targetSchools.size, "recipients" to recipients),
        )
        return AnnouncementResultDto(targetSchools.size, recipients)
    }

    /* --------------------------------- Audit -------------------------------- */

    fun listAudit(filter: AuditFilter, page: PageRequest): Page<AuditRowDto> {
        val conditions = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any>()
        filter.schoolId?.let {
            conditions += "a.school_id = :school"
            params["school"] = it
        }
        filter.action?.takeIf { it.isNotBlank() }?.let {
            conditions += "a.action = :action"
            params["action"] = it.trim()
        }
        filter.actorId?.let {
            conditions += "a.actor_id = :actor"
            params["actor"] = it
        }
        filter.entityType?.takeIf { it.isNotBlank() }?.let {
            conditions += "a.entity_type = :entity"
            params["entity"] = it.trim()
        }
        filter.from?.let {
            conditions += "a.created_at >= :from"
            params["from"] = it.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        }
        filter.to?.let {
            conditions += "a.created_at < :to"
            params["to"] = it.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
        }
        val where = conditions.joinToString(" and ")

        val count = em.createNativeQuery("select count(*) from audit_log a where $where")
        params.forEach { (k, v) -> count.setParameter(k, v) }
        val total = count.singleResult.asLong()
        if (total == 0L) return Page.empty(page.page, page.size)

        val query = em.createNativeQuery(
            """
            select a.id, a.school_id, s.name, a.actor_id, trim(coalesce(u.first_name, '') || ' ' || coalesce(u.last_name, '')),
                   a.actor_role, a.action, a.entity_type, a.entity_id, a.payload::text, host(a.ip_address), a.created_at
            from audit_log a
            left join schools s on s.id = a.school_id
            left join users u on u.id = a.actor_id
            where $where
            order by a.created_at desc, a.id desc
            limit :limit offset :offset
            """.trimIndent(),
        )
        params.forEach { (k, v) -> query.setParameter(k, v) }
        query.setParameter("limit", page.size)
        query.setParameter("offset", page.offset)

        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        val items = rows.map {
            AuditRowDto(
                id = (it[0] as Number).toLong(),
                schoolId = it[1] as UUID?,
                schoolName = it[2] as String?,
                actorId = it[3] as UUID?,
                actorName = (it[4] as String?)?.takeIf { name -> name.isNotBlank() },
                actorRole = it[5] as String?,
                action = it[6] as String,
                entityType = it[7] as String,
                entityId = it[8] as String?,
                payload = Json.read(it[9] as String?, emptyMap()),
                ipAddress = it[10] as String?,
                createdAt = Sql.requireInstant(it[11]),
            )
        }
        return Page(items, page.page, page.size, total)
    }

    fun auditActions(): List<String> {
        @Suppress("UNCHECKED_CAST")
        return em.createNativeQuery("select distinct action from audit_log order by 1").resultList as List<String>
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
