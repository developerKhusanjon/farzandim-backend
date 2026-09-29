package uz.farzandim.admin.service

import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import uz.farzandim.admin.dto.PlatformUserDto
import uz.farzandim.admin.dto.SubscriptionDto
import uz.farzandim.shared.domain.AuditEntry
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.SchoolAdminLevel
import uz.farzandim.shared.domain.SchoolSubscription
import uz.farzandim.shared.domain.SubscriptionStatus
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.repo.AuditRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.util.Json
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** So'rov kimdan kelgani — audit yozuvi uchun. */
@RequestScoped
class RequestInfo {
    var clientIp: String? = null
}

/**
 * Mijoz IP manzilini ushlab qoladi.
 *
 * `X-Forwarded-For` ni to'g'ridan-to'g'ri o'qimaymiz — uni istalgan mijoz
 * yuborib, audit jurnaliga soxta IP yozdira olardi. Proksi ortida
 * (DigitalOcean App Platform, nginx) `HTTP_PROXY_FORWARDING=true` qilinadi va
 * Quarkus ishonchli proksidan kelgan sarlavhani `remoteAddress` ga o'zi qo'yadi.
 * `audit_log.ip_address` — `inet`, shuning uchun IP'ga o'xshamagan qiymat yozilmaydi.
 */
class ClientIpCapture {

    @Inject lateinit var info: RequestInfo

    @ServerRequestFilter
    fun capture(request: HttpServerRequest) {
        info.clientIp = request.remoteAddress()?.host()?.takeIf { IP_PATTERN.matches(it) }
    }

    companion object {
        private val IP_PATTERN = Regex("^[0-9a-fA-F:.]{2,45}$")
    }
}

/**
 * Har bir o'zgarish va shaxsiy ma'lumotni ko'rish `audit_log` ga yoziladi:
 * kim, qaysi maktabda, nima qildi, qaysi IP'dan.
 */
@ApplicationScoped
class AuditRecorder {

    @Inject lateinit var audit: AuditRepository
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var requestInfo: RequestInfo

    @Transactional
    fun record(
        action: String,
        entityType: String,
        entityId: String?,
        schoolId: UUID?,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        audit.persist(
            AuditEntry().apply {
                this.schoolId = schoolId
                this.actorId = currentUser.userIdOrNull
                this.actorRole = runCatching { currentUser.role.name }.getOrNull()
                this.action = action
                this.entityType = entityType
                this.entityId = entityId
                this.payload = Json.write(payload)
                this.ipAddress = runCatching { requestInfo.clientIp }.getOrNull()
            },
        )
    }
}

/** Entity → DTO. */
object AdminMappers {

    fun user(user: UserAccount, schoolName: String? = null) = PlatformUserDto(
        id = user.id,
        schoolId = user.schoolId,
        schoolName = schoolName,
        role = user.role.name,
        adminLevel = if (user.role == Role.SCHOOL_ADMIN) user.adminLevel ?: SchoolAdminLevel.SUPER else null,
        firstName = user.firstName,
        lastName = user.lastName,
        username = user.username,
        email = user.email,
        phone = user.phone,
        status = user.status,
        mustChangePassword = user.mustChangePassword,
        lastLoginAt = user.lastLoginAt,
        createdAt = user.createdAt,
    )

    fun subscription(entity: SchoolSubscription, schoolName: String? = null): SubscriptionDto {
        val months = ChronoUnit.MONTHS.between(entity.startsOn, entity.endsOn).toInt().coerceAtLeast(1)
        return SubscriptionDto(
            id = entity.id,
            schoolId = entity.schoolId,
            schoolName = schoolName,
            planCode = entity.planCode,
            startsOn = entity.startsOn,
            endsOn = entity.endsOn,
            months = months,
            studentCount = entity.studentCount,
            amountUzs = entity.amountUzs,
            monthlyUzs = entity.amountUzs / months,
            discountPercent = entity.discountPercent,
            status = entity.status,
            effectiveStatus = effectiveStatus(entity),
            paymentStatus = entity.paymentStatus,
            paidOn = entity.paidOn,
            paymentMethod = entity.paymentMethod,
            paymentReference = entity.paymentReference,
            note = entity.note,
            cancelledAt = entity.cancelledAt,
            createdAt = entity.createdAt,
        )
    }

    /** Bazadagi holat sanaga qarab aniqlashtiriladi — alohida tungi job kerak emas. */
    fun effectiveStatus(entity: SchoolSubscription, today: LocalDate = LocalDate.now()): SubscriptionStatus =
        when {
            entity.status != SubscriptionStatus.ACTIVE -> entity.status
            entity.endsOn.isBefore(today) -> SubscriptionStatus.EXPIRED
            entity.startsOn.isAfter(today) -> SubscriptionStatus.PENDING
            else -> SubscriptionStatus.ACTIVE
        }
}

/** Native so'rov qiymatlarini o'qish. */
internal fun Any?.asLong(): Long = (this as? Number)?.toLong() ?: 0L

internal fun Any?.asDoubleOrNull(): Double? = (this as? Number)?.toDouble()

internal fun Double?.round1(): Double? = this?.let { Math.round(it * 10.0) / 10.0 }
