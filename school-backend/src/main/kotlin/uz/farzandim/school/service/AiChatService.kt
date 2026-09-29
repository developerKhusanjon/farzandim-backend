package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.AiConversationBody
import uz.farzandim.school.dto.AiConversationDto
import uz.farzandim.school.dto.AiMessageBody
import uz.farzandim.school.dto.AiMessageDto
import uz.farzandim.school.dto.AiSearchBody
import uz.farzandim.shared.ai.AiService
import uz.farzandim.shared.ai.AiUsageService
import uz.farzandim.shared.ai.EmbeddingService
import uz.farzandim.shared.ai.JobTypes
import uz.farzandim.shared.ai.Prompts
import uz.farzandim.shared.ai.SemanticHit
import uz.farzandim.shared.analytics.PerformanceService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.AiAuthor
import uz.farzandim.shared.domain.AiContextType
import uz.farzandim.shared.domain.AiConversation
import uz.farzandim.shared.domain.AiMessage
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.platform.PlanAccess
import uz.farzandim.shared.platform.PlanFeatures
import uz.farzandim.shared.repo.AiConversationRepository
import uz.farzandim.shared.repo.AiMessageRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.util.Tx
import java.time.Instant
import java.util.UUID

/**
 * AI chat yordamchisi.
 *
 * Kontekst modelga faqat KERAKLI minimal ko'rinishda beriladi
 * (docs/05-ai-prompts.md, 9-bo'lim): o'quvchi ismi emas, raqamlari.
 * Model javobi hech qanday shaxsiy ma'lumotni "eslab qolmasligi" uchun
 * suhbat tarixi ham faqat oxirgi 20 xabar bilan cheklangan.
 */
@ApplicationScoped
class AiChatService {

    @Inject lateinit var conversations: AiConversationRepository
    @Inject lateinit var messages: AiMessageRepository
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var ai: AiService
    @Inject lateinit var aiUsage: AiUsageService
    @Inject lateinit var planAccess: PlanAccess
    @Inject lateinit var embeddings: EmbeddingService
    @Inject lateinit var performance: PerformanceService
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var access: AccessControl

    companion object {
        private const val HISTORY_LIMIT = 20
    }

    fun list(): List<AiConversationDto> =
        conversations.listForUser(currentUser.userId).map { toDto(it) }

    @Transactional
    fun create(schoolId: UUID, body: AiConversationBody): AiConversationDto {
        val entity = AiConversation().apply {
            this.schoolId = schoolId
            this.userId = currentUser.userId
            this.title = body.title?.trim().orEmpty().ifBlank { "Yangi suhbat" }
            this.contextType = runCatching { AiContextType.valueOf(body.contextType) }
                .getOrDefault(AiContextType.GENERAL)
            this.contextId = body.contextId
        }
        conversations.persist(entity)
        return toDto(entity)
    }

    fun messages(conversationId: UUID): List<AiMessageDto> {
        requireConversation(conversationId)
        return messages.listForConversation(conversationId).map { toDto(it) }
    }

    /**
     * Savol-javob. AI javobi kutilayotganda DB tranzaksiyasi ochiq turmaydi
     * (`Tx` izohiga qarang). Savol va javob AI muvaffaqiyatli javob bergach
     * birga yoziladi — AI yiqilsa, javobsiz savol suhbatda osilib qolmaydi.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun ask(schoolId: UUID, conversationId: UUID, body: AiMessageBody): AiMessageDto {
        val question = body.content.trim()
        val askedAt = Instant.now()

        val (system, userMessage) = Tx.new {
            aiUsage.assertQuota(schoolId, currentUser.userId, PlanFeatures.AI_CHAT)
            val conversation = requireConversation(conversationId)

            val history = messages.lastMessages(conversationId, HISTORY_LIMIT)
            val user = users.findById(currentUser.userId)
            val system = Prompts.chatSystem(
                role = currentUser.role.name,
                name = user?.let { "${it.lastName} ${it.firstName}" } ?: "—",
                contextType = conversation.contextType.name,
                contextSummary = contextSummary(schoolId, conversation),
            )

            val transcript = history.joinToString("\n\n") { message ->
                val who = if (message.author == AiAuthor.USER) "Foydalanuvchi" else "AI"
                "$who: ${message.content}"
            }
            system to "$transcript\n\nFoydalanuvchi: $question\n\nAI:".trimStart()
        }

        val result = ai.complete(
            feature = JobTypes.CHAT,
            schoolId = schoolId,
            system = system,
            userMessage = userMessage,
        )

        return Tx.new {
            val conversation = requireConversation(conversationId)
            messages.persist(
                AiMessage().apply {
                    this.conversationId = conversationId
                    this.author = AiAuthor.USER
                    this.content = question
                    this.createdAt = askedAt
                },
            )
            val answer = AiMessage().apply {
                this.conversationId = conversationId
                this.author = AiAuthor.AI
                this.content = result.text
                this.model = result.model
                this.inputTokens = result.inputTokens
                this.outputTokens = result.outputTokens
            }
            messages.persist(answer)

            if (conversation.title == "Yangi suhbat") {
                conversation.title = question.take(60)
            }
            conversation.updatedAt = Instant.now()
            toDto(answer)
        }
    }

    /**
     * Kontekst xulosasi — modelga faqat raqamlar ketadi, ism emas.
     * "Bu o'quvchi" deb gapiriladi.
     */
    private fun contextSummary(schoolId: UUID, conversation: AiConversation): String {
        val contextId = conversation.contextId ?: return "Aniq obyekt tanlanmagan."
        return when (conversation.contextType) {
            AiContextType.STUDENT -> {
                val student = students.findInSchool(contextId, schoolId) ?: return "O'quvchi topilmadi."
                student.groupId?.let { access.assertCanTouchGroup(it) }
                val perf = performance.performance(schoolId, contextId, PerformancePeriod.MONTH)
                buildString {
                    appendLine("O'quvchi: ${student.gradeLevel}-sinf")
                    appendLine("Umumiy ball: ${perf.academicScore}%, davomat: ${perf.attendanceRate}%")
                    appendLine("Fanlar: " + perf.bySubject.joinToString(", ") { "${it.subjectName} ${it.score}%" })
                    appendLine("Kuchli mavzular: " + perf.strengths.joinToString(", ").ifBlank { "—" })
                    appendLine("E'tibor talab qiladi: " + perf.needsAttention.joinToString(", ").ifBlank { "—" })
                }
            }
            else -> "Kontekst: ${conversation.contextType.name} ($contextId)"
        }
    }

    /**
     * Semantik qidiruv — oddiy qidiruvdan ALOHIDA.
     * Bu yerda "ma'noga ko'ra" topiladi; ism/telefon bo'yicha qidiruv
     * `/students?q=` orqali, pg_trgm bilan ketadi.
     */
    fun semanticSearch(schoolId: UUID, body: AiSearchBody): List<SemanticHit> {
        // Tarif (vectorSearch) + maktab sozlamasi + `ai.vector_search` bayrog'i
        planAccess.assertAiAllowed(schoolId, PlanFeatures.VECTOR_SEARCH)
        if (!embeddings.enabled) {
            throw ApiException(
                uz.farzandim.shared.api.ErrorCode.AI_UNAVAILABLE,
                "Semantik qidiruv yoqilmagan (farzandim.embedding.enabled)",
            )
        }
        body.studentId?.let {
            val student = students.requireInSchool(it, schoolId, "O'quvchi")
            student.groupId?.let { groupId -> access.assertCanTouchGroup(groupId) }
        }
        return embeddings.search(schoolId, body.query, body.entityTypes, body.studentId, body.limit.coerceIn(1, 50))
    }

    /** Kontekstga qarab taklif chiplari. */
    fun suggestions(context: String?): List<String> = when (context) {
        "STUDENT" -> listOf(
            "Bu o'quvchining zaif tomonlarini tushuntir",
            "Individual rivojlantiruvchi topshiriq tavsiya qil",
            "Ota-onaga nima deb aytsam bo'ladi?",
        )
        "LESSON" -> listOf(
            "Dars xulosasini tayyorla",
            "Mavzu bo'yicha terminlar ro'yxatini ber",
            "Guruh qanday o'zlashtirdi?",
        )
        "HOMEWORK" -> listOf(
            "Vazifani soddalashtir",
            "Ota-onalar uchun izoh yoz",
            "Qiynalayotgan o'quvchilarni aniqla",
        )
        else -> listOf(
            "Bu hafta e'tibor talab qiladigan o'quvchilar kimlar?",
            "Qaysi fan bo'yicha natijalar pasaydi?",
            "Guruh uchun haftalik hisobot tayyorla",
        )
    }

    private fun requireConversation(id: UUID): AiConversation =
        conversations.findForUser(id, currentUser.userId) ?: throw ApiException.notFound("Suhbat", id)

    private fun toDto(entity: AiConversation) = AiConversationDto(
        id = entity.id,
        userId = entity.userId,
        title = entity.title,
        contextType = entity.contextType.name,
        contextId = entity.contextId,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    private fun toDto(entity: AiMessage) = AiMessageDto(
        id = entity.id,
        conversationId = entity.conversationId,
        author = entity.author.name,
        content = entity.content,
        suggestions = entity.suggestions.toList(),
        terms = entity.terms.toList(),
        createdAt = entity.createdAt,
    )
}
