package uz.farzandim.shared.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "ai_conversations")
class AiConversation : AuditedEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "user_id", nullable = false) lateinit var userId: UUID
    @Column(name = "title", nullable = false) var title: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "context_type", nullable = false)
    var contextType: AiContextType = AiContextType.GENERAL

    @Column(name = "context_id") var contextId: UUID? = null
    @Column(name = "archived", nullable = false) var archived: Boolean = false
}

@Entity
@Table(name = "ai_messages")
class AiMessage : BaseEntity() {
    @Column(name = "conversation_id", nullable = false) lateinit var conversationId: UUID

    @Enumerated(EnumType.STRING)
    @Column(name = "author", nullable = false)
    var author: AiAuthor = AiAuthor.USER

    @Column(name = "content", nullable = false) var content: String = ""
    @Column(name = "suggestions", nullable = false) var suggestions: Array<String> = emptyArray()
    @Column(name = "terms", nullable = false) var terms: Array<String> = emptyArray()
    @Column(name = "model") var model: String? = null
    @Column(name = "input_tokens") var inputTokens: Int? = null
    @Column(name = "output_tokens") var outputTokens: Int? = null
}

/**
 * AI job — barcha og'ir AI ishlari shu navbatdan o'tadi.
 *
 * Nega Valkey emas, PostgreSQL: job holati tranzaksion bo'lishi kerak
 * (masalan "dars xulosasi tayyorlandi" + "xulosa lessons ga yozildi" birga),
 * va qayta ishga tushirishdan keyin ham tiklanishi shart.
 * Valkey faqat keshi, limit va lock uchun — u yo'qolsa ham ma'lumot yo'qolmaydi.
 */
@Entity
@Table(name = "ai_jobs")
class AiJob : AuditedEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "created_by") var createdBy: UUID? = null
    @Column(name = "type", nullable = false) lateinit var type: String

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: JobStatus = JobStatus.QUEUED

    @Column(name = "priority", nullable = false) var priority: Int = 100
    @Column(name = "entity_type") var entityType: String? = null
    @Column(name = "entity_id") var entityId: UUID? = null
    @Column(name = "idempotency_key") var idempotencyKey: String? = null
    @Column(name = "payload", nullable = false) var payload: String = "{}"
    @Column(name = "result") var result: String? = null
    @Column(name = "error_code") var errorCode: String? = null
    @Column(name = "error_message") var errorMessage: String? = null
    @Column(name = "attempts", nullable = false) var attempts: Int = 0
    @Column(name = "max_attempts", nullable = false) var maxAttempts: Int = 3
    @Column(name = "locked_by") var lockedBy: String? = null
    @Column(name = "locked_at") var lockedAt: Instant? = null
    @Column(name = "run_after", nullable = false) var runAfter: Instant = Instant.now()
    @Column(name = "started_at") var startedAt: Instant? = null
    @Column(name = "finished_at") var finishedAt: Instant? = null
    @Column(name = "model") var model: String? = null
    @Column(name = "input_tokens") var inputTokens: Int? = null
    @Column(name = "output_tokens") var outputTokens: Int? = null
    @Column(name = "cost_micros") var costMicros: Long? = null
}

@Embeddable
class AiUsageId : Serializable {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "usage_date", nullable = false) lateinit var usageDate: LocalDate
    @Column(name = "feature", nullable = false) lateinit var feature: String

    override fun equals(other: Any?): Boolean =
        other is AiUsageId && other.schoolId == schoolId && other.usageDate == usageDate && other.feature == feature

    override fun hashCode(): Int = (31 * schoolId.hashCode() + usageDate.hashCode()) * 31 + feature.hashCode()
}

@Entity
@Table(name = "ai_usage_daily")
class AiUsageDaily {
    @EmbeddedId
    var id: AiUsageId = AiUsageId()

    @Column(name = "calls", nullable = false) var calls: Int = 0
    @Column(name = "input_tokens", nullable = false) var inputTokens: Long = 0
    @Column(name = "output_tokens", nullable = false) var outputTokens: Long = 0
    @Column(name = "cost_micros", nullable = false) var costMicros: Long = 0
}

@Entity
@Table(name = "messages")
class MessageEntity : BaseEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "sender_id") var senderId: UUID? = null
    @Column(name = "sender_role") var senderRole: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false)
    var recipientType: RecipientType = RecipientType.PARENT

    @Column(name = "recipient_group_id") var recipientGroupId: UUID? = null
    @Column(name = "subject", nullable = false) var subject: String = ""
    @Column(name = "body", nullable = false) var body: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    var category: MessageCategory = MessageCategory.GENERAL

    @Column(name = "related_entity_type") var relatedEntityType: String? = null
    @Column(name = "related_entity_id") var relatedEntityId: UUID? = null
    @Column(name = "attachments", nullable = false) var attachments: String = "[]"
    @Column(name = "sent_at", nullable = false) var sentAt: Instant = Instant.now()
}

@Embeddable
class MessageRecipientId : Serializable {
    @Column(name = "message_id", nullable = false) lateinit var messageId: UUID
    @Column(name = "user_id", nullable = false) lateinit var userId: UUID

    override fun equals(other: Any?): Boolean =
        other is MessageRecipientId && other.messageId == messageId && other.userId == userId

    override fun hashCode(): Int = 31 * messageId.hashCode() + userId.hashCode()
}

@Entity
@Table(name = "message_recipients")
class MessageRecipient {
    @EmbeddedId
    var id: MessageRecipientId = MessageRecipientId()

    @Column(name = "student_id") var studentId: UUID? = null
    @Column(name = "read_at") var readAt: Instant? = null
}

@Entity
@Table(name = "notifications")
class Notification : BaseEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "user_id", nullable = false) lateinit var userId: UUID
    @Column(name = "type", nullable = false) var type: String = "GENERAL"
    @Column(name = "title", nullable = false) var title: String = ""
    @Column(name = "body", nullable = false) var body: String = ""
    @Column(name = "color_key", nullable = false) var colorKey: String = "blue"
    @Column(name = "icon_key", nullable = false) var iconKey: String = "bell"
    @Column(name = "payload", nullable = false) var payload: String = "{}"
    @Column(name = "link") var link: String? = null
    @Column(name = "is_read", nullable = false) var isRead: Boolean = false
    @Column(name = "push_sent_at") var pushSentAt: Instant? = null
}

@Entity
@Table(name = "files")
class FileObject : BaseEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "owner_id") var ownerId: UUID? = null
    @Column(name = "bucket", nullable = false) lateinit var bucket: String
    @Column(name = "object_key", nullable = false) lateinit var objectKey: String
    @Column(name = "original_name", nullable = false) var originalName: String = ""
    @Column(name = "content_type", nullable = false) var contentType: String = "application/octet-stream"
    @Column(name = "size_bytes", nullable = false) var sizeBytes: Long = 0
    @Column(name = "checksum") var checksum: String? = null
    @Column(name = "purpose", nullable = false) var purpose: String = "GENERAL"
    @Column(name = "entity_type") var entityType: String? = null
    @Column(name = "entity_id") var entityId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: FileStatus = FileStatus.PENDING

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false)
    var scanStatus: ScanStatus = ScanStatus.SKIPPED

    @Column(name = "confirmed_at") var confirmedAt: Instant? = null
    @Column(name = "deleted_at") var deletedAt: Instant? = null
}

@Entity
@Table(name = "subscription_plans")
class SubscriptionPlan {
    @jakarta.persistence.Id
    @Column(name = "code", nullable = false)
    lateinit var code: String

    @Column(name = "title", nullable = false) var title: String = ""
    @Column(name = "price_per_student_uzs", nullable = false) var pricePerStudentUzs: Long = 0
    @Column(name = "min_monthly_uzs", nullable = false) var minMonthlyUzs: Long = 0
    @Column(name = "max_students") var maxStudents: Int? = null
    @Column(name = "ai_daily_limit", nullable = false) var aiDailyLimit: Int = 0
    @Column(name = "features", nullable = false) var features: String = "{}"
    @Column(name = "active", nullable = false) var active: Boolean = true
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now()
}

@Entity
@Table(name = "school_subscriptions")
class SchoolSubscription : BaseEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "plan_code", nullable = false) lateinit var planCode: String
    @Column(name = "starts_on", nullable = false) lateinit var startsOn: LocalDate
    @Column(name = "ends_on", nullable = false) lateinit var endsOn: LocalDate
    @Column(name = "student_count", nullable = false) var studentCount: Int = 0
    @Column(name = "amount_uzs", nullable = false) var amountUzs: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: SubscriptionStatus = SubscriptionStatus.ACTIVE

    @Column(name = "note") var note: String? = null
    @Column(name = "created_by") var createdBy: UUID? = null

    /* ---- to'lov (V9) ---- */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    var paymentStatus: PaymentStatus = PaymentStatus.UNPAID

    @Column(name = "paid_on") var paidOn: LocalDate? = null
    @Column(name = "payment_method") var paymentMethod: String? = null
    @Column(name = "payment_reference") var paymentReference: String? = null
    @Column(name = "discount_percent", nullable = false) var discountPercent: Int = 0
    @Column(name = "cancelled_at") var cancelledAt: Instant? = null
}

@Entity
@Table(name = "feature_flags")
class FeatureFlag {
    @jakarta.persistence.Id
    @Column(name = "key", nullable = false)
    lateinit var key: String

    @Column(name = "description", nullable = false) var description: String = ""
    @Column(name = "enabled", nullable = false) var enabled: Boolean = false

    /** jsonb: `{ "<schoolId>": true }` — maktab bo'yicha istisno. */
    @Column(name = "school_overrides", nullable = false) var schoolOverrides: String = "{}"

    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now()
}
