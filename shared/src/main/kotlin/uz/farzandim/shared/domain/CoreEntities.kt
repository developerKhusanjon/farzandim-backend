package uz.farzandim.shared.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import uz.farzandim.shared.util.Uuids
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Bog'lanishlar JPA assotsiatsiyasi emas, oddiy `UUID` ustun sifatida turadi.
 *
 * Nega: lazy-loading tuzoqlari, N+1 va "kim qachon qaysi obyektni tortdi"
 * noaniqligi yo'qoladi; har bir so'rov aniq yoziladi va — eng muhimi —
 * har bir repository so'rovi `school_id` shartini majburan qo'shadi.
 * Tenant izolyatsiyasi assotsiatsiya ortida yashirinib qolmaydi.
 */
@MappedSuperclass
abstract class BaseEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = Uuids.v7()

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

@MappedSuperclass
abstract class AuditedEntity : BaseEntity() {
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    fun touch() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "schools")
class School : AuditedEntity() {
    @Column(name = "name", nullable = false) lateinit var name: String
    @Column(name = "short_name", nullable = false) lateinit var shortName: String
    @Column(name = "logo_url") var logoUrl: String? = null
    @Column(name = "address", nullable = false) var address: String = ""
    @Column(name = "phone", nullable = false) var phone: String = ""
    @Column(name = "email", nullable = false) var email: String = ""
    @Column(name = "academic_year", nullable = false) lateinit var academicYear: String

    @Enumerated(EnumType.STRING)
    @Column(name = "grading_system", nullable = false)
    var gradingSystem: GradingSystem = GradingSystem.HUNDRED

    @Enumerated(EnumType.STRING)
    @Column(name = "locale", nullable = false)
    var locale: Locale = Locale.uz

    @Column(name = "timezone", nullable = false) var timezone: String = "Asia/Tashkent"

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_plan", nullable = false)
    var subscriptionPlan: SubscriptionPlanCode = SubscriptionPlanCode.BASIC

    @Column(name = "subscription_until") var subscriptionUntil: LocalDate? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: SchoolStatus = SchoolStatus.ACTIVE

    /** jsonb — docs/02-data-model.md dagi `settings` obyekti. */
    @Column(name = "settings", nullable = false) var settings: String = "{}"

    @Column(name = "ai_daily_limit", nullable = false) var aiDailyLimit: Int = 200

    /* ---- yuridik shaxs va shartnoma (V9) ---- */
    @Column(name = "legal_name", nullable = false) var legalName: String = ""
    @Column(name = "tin") var tin: String? = null
    @Column(name = "director_name", nullable = false) var directorName: String = ""
    @Column(name = "region", nullable = false) var region: String = ""
    @Column(name = "city", nullable = false) var city: String = ""
    @Column(name = "contact_person", nullable = false) var contactPerson: String = ""
    @Column(name = "contact_phone", nullable = false) var contactPhone: String = ""
    @Column(name = "contract_number") var contractNumber: String? = null
    @Column(name = "contract_signed_on") var contractSignedOn: LocalDate? = null

    /** To'xtatish yoki arxivlash sababi — maktab admini kirolmaganda ko'rsatiladi. */
    @Column(name = "status_reason") var statusReason: String? = null
}

@Entity
@Table(name = "users")
class UserAccount : AuditedEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    lateinit var role: Role

    @Column(name = "first_name", nullable = false) lateinit var firstName: String
    @Column(name = "last_name", nullable = false) lateinit var lastName: String
    @Column(name = "middle_name") var middleName: String? = null
    @Column(name = "avatar_url") var avatarUrl: String? = null
    @Column(name = "username") var username: String? = null
    @Column(name = "email") var email: String? = null
    @Column(name = "phone") var phone: String? = null
    @Column(name = "password_hash") var passwordHash: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: UserStatus = UserStatus.ACTIVE

    @Enumerated(EnumType.STRING)
    @Column(name = "locale", nullable = false)
    var locale: Locale = Locale.uz

    @Column(name = "must_change_password", nullable = false) var mustChangePassword: Boolean = false
    @Column(name = "last_login_at") var lastLoginAt: Instant? = null

    /** Faqat SCHOOL_ADMIN uchun. V9 dan oldingi yozuvlarda `null` — SUPER deb hisoblanadi. */
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_level")
    var adminLevel: SchoolAdminLevel? = null

    /** GENERATED ALWAYS ustun — faqat o'qish uchun, trigram qidiruvda ishlatiladi. */
    @Column(name = "search_text", insertable = false, updatable = false) var searchText: String? = null

    val fullName: String get() = "$lastName $firstName"

    /** Maktab superadmini: boshqa adminlarni boshqara oladi. */
    val isSchoolSuperAdmin: Boolean
        get() = role == Role.SCHOOL_ADMIN && (adminLevel ?: SchoolAdminLevel.SUPER) == SchoolAdminLevel.SUPER
}

@Entity
@Table(name = "refresh_tokens")
class RefreshToken : BaseEntity() {
    @Column(name = "user_id", nullable = false) lateinit var userId: UUID
    @Column(name = "family_id", nullable = false) lateinit var familyId: UUID
    @Column(name = "token_hash", nullable = false) lateinit var tokenHash: String
    @Column(name = "audience", nullable = false) lateinit var audience: String
    @Column(name = "device_info") var deviceInfo: String? = null
    @Column(name = "ip_address") var ipAddress: String? = null
    @Column(name = "expires_at", nullable = false) lateinit var expiresAt: Instant
    @Column(name = "revoked_at") var revokedAt: Instant? = null
    @Column(name = "revoke_reason") var revokeReason: String? = null
    @Column(name = "replaced_by") var replacedBy: UUID? = null

    val isActive: Boolean get() = revokedAt == null && expiresAt.isAfter(Instant.now())
}

@Entity
@Table(name = "otp_codes")
class OtpCode : BaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    lateinit var channel: OtpChannel

    @Column(name = "recipient", nullable = false) lateinit var recipient: String
    @Column(name = "code_hash", nullable = false) lateinit var codeHash: String

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    var purpose: OtpPurpose = OtpPurpose.LOGIN

    @Column(name = "attempts", nullable = false) var attempts: Int = 0
    @Column(name = "consumed_at") var consumedAt: Instant? = null
    @Column(name = "expires_at", nullable = false) lateinit var expiresAt: Instant
}

@Entity
@Table(name = "device_tokens")
class DeviceToken : BaseEntity() {
    @Column(name = "user_id", nullable = false) lateinit var userId: UUID
    @Column(name = "token", nullable = false) lateinit var token: String

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    lateinit var platform: DevicePlatform

    @Column(name = "locale", nullable = false) var locale: String = "uz"
    @Column(name = "app_version") var appVersion: String? = null
    @Column(name = "last_seen_at", nullable = false) var lastSeenAt: Instant = Instant.now()

    /** Ketma-ket muvaffaqiyatsiz yuborishlar — vaqtinchalik xatodan farqlash uchun. */
    @Column(name = "failure_count", nullable = false) var failureCount: Int = 0
    @Column(name = "disabled_at") var disabledAt: Instant? = null
    @Column(name = "last_error") var lastError: String? = null

    val active: Boolean get() = disabledAt == null
}

@Entity
@Table(name = "sms_messages")
class SmsMessage : BaseEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "user_id") var userId: UUID? = null
    @Column(name = "provider", nullable = false) lateinit var provider: String
    @Column(name = "recipient", nullable = false) lateinit var recipient: String

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    var purpose: MessagePurpose = MessagePurpose.OTP

    @Column(name = "parts", nullable = false) var parts: Int = 1

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: DeliveryStatus = DeliveryStatus.QUEUED

    @Column(name = "provider_message_id") var providerMessageId: String? = null
    @Column(name = "error_code") var errorCode: String? = null
    @Column(name = "error_message") var errorMessage: String? = null
    @Column(name = "sent_at") var sentAt: Instant? = null
    @Column(name = "delivered_at") var deliveredAt: Instant? = null
}

@Entity
@Table(name = "email_messages")
class EmailMessage : BaseEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "user_id") var userId: UUID? = null
    @Column(name = "provider", nullable = false) lateinit var provider: String
    @Column(name = "recipient", nullable = false) lateinit var recipient: String
    @Column(name = "subject", nullable = false) var subject: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    var purpose: MessagePurpose = MessagePurpose.OTP

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: DeliveryStatus = DeliveryStatus.QUEUED

    @Column(name = "error_code") var errorCode: String? = null
    @Column(name = "error_message") var errorMessage: String? = null
    @Column(name = "sent_at") var sentAt: Instant? = null
}

/** Platforma jamoasining maktab haqidagi ichki qaydi (qo'ng'iroq, uchrashuv, shartnoma). */
@Entity
@Table(name = "school_notes")
class SchoolNote : BaseEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "author_id") var authorId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    var kind: SchoolNoteKind = SchoolNoteKind.NOTE

    @Column(name = "body", nullable = false) lateinit var body: String
}

@Entity
@Table(name = "audit_log")
class AuditEntry {
    /**
     * `bigserial` ketma-ketligidan — IDENTITY emas: IDENTITY'da drayver
     * `INSERT ... RETURNING *` yuboradi va u butun jadvalni O'QISH huquqini
     * talab qiladi. Maktab va mobil servislar esa audit jurnaliga faqat
     * yozadi, o'qiy olmaydi (R__app_role_grants.sql).
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.SEQUENCE, generator = "audit_log_seq")
    @jakarta.persistence.SequenceGenerator(name = "audit_log_seq", sequenceName = "audit_log_id_seq", allocationSize = 1)
    var id: Long? = null

    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "actor_id") var actorId: UUID? = null
    @Column(name = "actor_role") var actorRole: String? = null
    @Column(name = "action", nullable = false) lateinit var action: String
    @Column(name = "entity_type", nullable = false) lateinit var entityType: String
    @Column(name = "entity_id") var entityId: String? = null
    @Column(name = "payload", nullable = false) var payload: String = "{}"
    @Column(name = "ip_address") var ipAddress: String? = null
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()
}
