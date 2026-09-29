package uz.farzandim.mobile.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.mobile.dto.AiChatBody
import uz.farzandim.mobile.dto.AiHomeworkResultDto
import uz.farzandim.mobile.dto.AiMessageDto
import uz.farzandim.shared.ai.AiJobService
import uz.farzandim.shared.ai.AiService
import uz.farzandim.shared.ai.AiUsageService
import uz.farzandim.shared.ai.JobSpec
import uz.farzandim.shared.ai.JobTypes
import uz.farzandim.shared.ai.Prompts
import uz.farzandim.shared.analytics.PerformanceService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.AiAuthor
import uz.farzandim.shared.domain.AiContextType
import uz.farzandim.shared.domain.AiConversation
import uz.farzandim.shared.domain.AiMessage
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.platform.PlanFeatures
import uz.farzandim.shared.repo.AiConversationRepository
import uz.farzandim.shared.repo.AiMessageRepository
import uz.farzandim.shared.repo.GroupRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.util.Tx
import java.time.Instant
import java.util.UUID

/**
 * Ota-ona uchun AI yordamchisi (docs/05-ai-prompts.md, 5-bo'lim).
 *
 * Uch rejim:
 *   SIMPLIFY — vazifani yengillashtirish (lekin bola o'rniga bajarmaslik)
 *   EXPLAIN  — ota-onaga "qanday tushuntirish"ni o'rgatish, javobni oshkor qilmasdan
 *   SOLVE    — to'liq yechim, lekin MAJBURIY ogohlantirish bilan
 *
 * SOLVE dagi ogohlantirish model javobiga emas, kodga bog'langan:
 * model uni tushirib qoldirsa ham foydalanuvchi ko'radi.
 */
@ApplicationScoped
class ParentAiService {

    @Inject lateinit var ai: AiService
    @Inject lateinit var aiJobs: AiJobService
    @Inject lateinit var aiUsage: AiUsageService
    @Inject lateinit var parents: ParentService
    @Inject lateinit var access: ParentAccess
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var performance: PerformanceService
    @Inject lateinit var conversations: AiConversationRepository
    @Inject lateinit var messages: AiMessageRepository

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun assist(homeworkId: UUID, mode: String): AiHomeworkResultDto {
        val normalized = mode.uppercase()
        val (schoolId, prompt) = Tx.new {
            val schoolId = access.profile().schoolId
            aiUsage.assertQuota(schoolId, currentUser.userId)

            val homework = parents.homeworkDetail(homeworkId)
            val gradeLevel = access.childIds()
                .mapNotNull { runCatching { access.requireChild(it) }.getOrNull() }
                .firstOrNull()?.gradeLevel ?: 5

            schoolId to when (normalized) {
                "SIMPLIFY" -> Prompts.simplify(homework.description, gradeLevel)
                "EXPLAIN" -> Prompts.explain(homework.description)
                "SOLVE" -> Prompts.solve(homework.description)
                else -> throw ApiException.validation(
                    "mode faqat SIMPLIFY, EXPLAIN yoki SOLVE bo'lishi mumkin",
                    mapOf("mode" to mode),
                )
            }
        }

        return aiJobs.runNow(
            JobSpec(
                type = JobTypes.HOMEWORK_ASSIST,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                entityType = "HOMEWORK",
                entityId = homeworkId,
                payload = mapOf("mode" to normalized),
            ),
        ) {
            val (node, result) = ai.completeJson(
                "${JobTypes.HOMEWORK_ASSIST}_$normalized", schoolId, Prompts.SYSTEM_RULES, prompt,
            )
            AiHomeworkResultDto(
                mode = normalized,
                warning = if (normalized == "SOLVE") Prompts.SOLVE_WARNING else null,
                content = node.path("content").asText(""),
                steps = node.path("steps").mapNotNull { it.asText(null) },
            ) to result
        }
    }

    /**
     * Ota-ona chati. Mustaqil foydalanuvchi uchun ham ishlaydi —
     * u holda kontekst bo'lmaydi, faqat umumiy ta'lim yordami.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun chat(body: AiChatBody): AiMessageDto {
        val question = body.content.trim()
        val askedAt = Instant.now()

        // 1. Kontekst va tarix — qisqa tranzaksiya; AI kutilayotganda ulanish band emas
        val (schoolId, system, transcript) = Tx.new {
            val schoolId = access.profile().schoolId
            aiUsage.assertQuota(schoolId, currentUser.userId, PlanFeatures.AI_CHAT)

            val conversation = conversations.listForUser(currentUser.userId).firstOrNull()
            val history = conversation?.let { messages.lastMessages(it.id, 20) }.orEmpty()
            val transcript = history.joinToString("\n\n") {
                (if (it.author == AiAuthor.USER) "Ota-ona: " else "AI: ") + it.content
            }

            val system = Prompts.chatSystem(
                role = "PARENT",
                name = "Ota-ona",
                contextType = if (body.childId != null) "STUDENT" else "GENERAL",
                contextSummary = body.childId?.let { childContext(it) } ?: "Farzand tanlanmagan.",
            )
            Triple(schoolId, system, transcript)
        }

        val result = ai.complete(JobTypes.CHAT, schoolId, system, "$transcript\n\nOta-ona: $question\n\nAI:".trimStart())

        // 3. Savol va javob birga — AI yiqilsa, javobsiz savol qolmaydi
        return Tx.new {
            val conversation = conversations.listForUser(currentUser.userId).firstOrNull()
                ?: AiConversation().apply {
                    this.schoolId = schoolId
                    this.userId = currentUser.userId
                    this.title = "Ota-ona yordamchisi"
                    this.contextType = AiContextType.GENERAL
                }.also { conversations.persist(it) }

            messages.persist(
                AiMessage().apply {
                    this.conversationId = conversation.id
                    this.author = AiAuthor.USER
                    this.content = question
                    this.createdAt = askedAt
                },
            )
            val answer = AiMessage().apply {
                this.conversationId = conversation.id
                this.author = AiAuthor.AI
                this.content = result.text
                this.model = result.model
                this.inputTokens = result.inputTokens
                this.outputTokens = result.outputTokens
            }
            messages.persist(answer)
            conversation.updatedAt = Instant.now()

            AiMessageDto(answer.id, answer.author.name, answer.content, emptyList(), answer.createdAt)
        }
    }

    fun history(limit: Int): List<AiMessageDto> {
        val conversation = conversations.listForUser(currentUser.userId).firstOrNull() ?: return emptyList()
        return messages.lastMessages(conversation.id, limit).map {
            AiMessageDto(it.id, it.author.name, it.content, it.terms.toList(), it.createdAt)
        }
    }

    /** Modelga ism emas, raqamlar beriladi (docs/05-ai-prompts.md, 9-bo'lim). */
    private fun childContext(childId: UUID): String {
        val child = access.requireChild(childId)
        val perf = performance.performance(child.schoolId, childId, PerformancePeriod.MONTH)
        return buildString {
            appendLine("Farzand: ${child.gradeLevel}-sinf o'quvchisi")
            appendLine("Umumiy o'zlashtirish: ${perf.academicScore}%, davomat: ${perf.attendanceRate}%")
            appendLine("Fanlar: " + perf.bySubject.joinToString(", ") { "${it.subjectName} ${it.score}%" })
            if (perf.needsAttention.isNotEmpty()) {
                appendLine("Qo'shimcha mashq kerak: " + perf.needsAttention.take(4).joinToString(", "))
            }
        }
    }
}
