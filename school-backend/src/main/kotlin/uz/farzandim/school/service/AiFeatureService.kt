package uz.farzandim.school.service

import com.fasterxml.jackson.databind.JsonNode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.AssessmentGenerateBody
import uz.farzandim.school.dto.HomeworkDraftDto
import uz.farzandim.school.dto.HomeworkGenerateBody
import uz.farzandim.school.dto.HomeworkVariantBody
import uz.farzandim.school.dto.LessonSummaryBody
import uz.farzandim.school.dto.LessonSummaryDraftDto
import uz.farzandim.school.dto.LessonTermDto
import uz.farzandim.school.dto.RecommendationDto
import uz.farzandim.shared.ai.AiJobService
import uz.farzandim.shared.ai.AiService
import uz.farzandim.shared.ai.AiUsageService
import uz.farzandim.shared.ai.EmbeddingService
import uz.farzandim.shared.ai.JobSpec
import uz.farzandim.shared.ai.JobTypes
import uz.farzandim.shared.ai.Prompts
import uz.farzandim.shared.analytics.PerformanceService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.AssessmentTask
import uz.farzandim.shared.domain.AssessmentTaskStatus
import uz.farzandim.shared.domain.AssignedTo
import uz.farzandim.shared.domain.CognitiveArea
import uz.farzandim.shared.domain.CognitiveRecommendation
import uz.farzandim.shared.domain.Difficulty
import uz.farzandim.shared.domain.MessageCategory
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.domain.SkillAssessment
import uz.farzandim.shared.domain.SkillSource
import uz.farzandim.shared.domain.SummaryStatus
import uz.farzandim.shared.notify.NotificationService
import uz.farzandim.shared.platform.PlanFeatures
import uz.farzandim.shared.repo.AssessmentTaskRepository
import uz.farzandim.shared.repo.GradeRepository
import uz.farzandim.shared.repo.InterestRepository
import uz.farzandim.shared.repo.RecommendationRepository
import uz.farzandim.shared.repo.SkillAssessmentRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.repo.TopicRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.util.Json
import uz.farzandim.shared.util.Tx
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * AI funksiyalari (docs/05-ai-prompts.md).
 *
 * Asosiy qoida: AI hech qachon to'g'ridan-to'g'ri ota-onaga chiqmaydi.
 * U qoralama tayyorlaydi (`AI_DRAFT`), o'qituvchi tahrirlaydi va
 * tasdiqlaydi (`APPROVED`), keyin yuboriladi (`SENT_TO_PARENTS`).
 * Shu sababli generatsiya va yuborish alohida endpointlar.
 */
@ApplicationScoped
class AiFeatureService {

    @Inject lateinit var ai: AiService
    @Inject lateinit var aiJobs: AiJobService
    @Inject lateinit var aiUsage: AiUsageService
    @Inject lateinit var embeddings: EmbeddingService
    @Inject lateinit var performance: PerformanceService
    @Inject lateinit var notifications: NotificationService
    @Inject lateinit var currentUser: CurrentUser

    @Inject lateinit var schedule: ScheduleService
    @Inject lateinit var homeworkService: HomeworkService
    @Inject lateinit var journal: JournalService
    @Inject lateinit var access: AccessControl
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var mappers: Mappers

    @Inject lateinit var students: StudentRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var topics: TopicRepository
    @Inject lateinit var grades: GradeRepository
    @Inject lateinit var tasks: AssessmentTaskRepository
    @Inject lateinit var skills: SkillAssessmentRepository
    @Inject lateinit var interests: InterestRepository
    @Inject lateinit var recommendations: RecommendationRepository

    /* ----------------------------- Dars xulosasi ---------------------------- */

    /*
     * AI metodlari DB tranzaksiyasini AI javobini kutib ushlab turmaydi —
     * `Tx` izohiga qarang: 1) qisqa o'qish, 2) AI tranzaksiyasiz, 3) qisqa yozish.
     */

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun generateLessonSummary(schoolId: UUID, lessonId: UUID): LessonSummaryDraftDto {
        val (topic, prompt) = Tx.new {
            lookups.load(schoolId)
            aiUsage.assertQuota(schoolId, currentUser.userId)

            val lesson = schedule.requireLesson(schoolId, lessonId)
            access.assertOwnsOrAdmin(lesson.teacherId)

            val topic = lesson.topicId?.let { topics.findInSchool(it, schoolId)?.title }
                ?: lesson.title.ifBlank { "Mavzu ko'rsatilmagan" }
            val group = lookups.group(lesson.groupId)
            val groupStudents = students.listByGroup(schoolId, lesson.groupId)
            val avg = performance.academicScoresFor(schoolId, groupStudents.map { it.id })
                .values.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0

            topic to Prompts.lessonSummary(
                subjectName = lookups.subjectName(lesson.subjectId),
                topic = topic,
                gradeLevel = group?.gradeLevel ?: 1,
                groupName = group?.name ?: "—",
                studentCount = groupStudents.size,
                avgScore = avg,
                teacherNotes = lesson.teacherNotes,
            )
        }

        val draft = aiJobs.runNow(
            JobSpec(
                type = JobTypes.LESSON_SUMMARY,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                entityType = "LESSON",
                entityId = lessonId,
                payload = mapOf("topic" to topic),
            ),
        ) {
            val (node, result) = ai.completeJson(JobTypes.LESSON_SUMMARY, schoolId, Prompts.SYSTEM_RULES, prompt)
            LessonSummaryDraftDto(
                summary = node.path("summary").asText(""),
                terms = readTerms(node.path("terms")),
            ) to result
        }

        Tx.new {
            val lesson = schedule.requireLesson(schoolId, lessonId)
            lesson.summary = draft.summary
            lesson.terms = Json.write(draft.terms)
            lesson.summaryStatus = SummaryStatus.AI_DRAFT
            lesson.touch()
        }
        return draft
    }

    @Transactional
    fun updateLessonSummary(schoolId: UUID, lessonId: UUID, body: LessonSummaryBody): LessonSummaryDraftDto {
        lookups.load(schoolId)
        val lesson = schedule.requireLesson(schoolId, lessonId)
        access.assertOwnsOrAdmin(lesson.teacherId)

        body.summary?.let { lesson.summary = it }
        body.terms?.let { lesson.terms = Json.write(it) }
        lesson.summaryStatus = SummaryStatus.APPROVED
        lesson.touch()

        return LessonSummaryDraftDto(lesson.summary.orEmpty(), mappers.terms(lesson.terms))
    }

    /** Ota-onalarga yuborish — faqat o'qituvchi tasdiqlagandan keyin. */
    @Transactional
    fun sendLessonSummary(schoolId: UUID, lessonId: UUID): LessonSummaryDraftDto {
        lookups.load(schoolId)
        val lesson = schedule.requireLesson(schoolId, lessonId)
        access.assertOwnsOrAdmin(lesson.teacherId)

        if (lesson.summary.isNullOrBlank()) {
            throw ApiException.validation("Xulosa bo'sh — avval tayyorlang va tahrirlang")
        }
        if (lesson.summaryStatus == SummaryStatus.AI_DRAFT) {
            throw ApiException.validation(
                "AI qoralamasi o'qituvchi tasdig'isiz yuborilmaydi. Avval tahrirlab saqlang.",
            )
        }

        val groupStudents = students.listByGroup(schoolId, lesson.groupId)
        notifications.notifyParentsOfStudents(
            groupStudents.map { it.id },
            schoolId,
            MessageCategory.LESSON_SUMMARY,
            title = "Dars xulosasi: ${lookups.subjectName(lesson.subjectId)}",
            body = lesson.summary!!.take(140),
            link = "/lessons/${lesson.id}",
            payload = mapOf("lessonId" to lesson.id.toString()),
        )

        lesson.summaryStatus = SummaryStatus.SENT_TO_PARENTS
        lesson.summarySentAt = Instant.now()
        lesson.touch()

        // Semantik qidiruv uchun indekslash — fonda.
        if (embeddings.enabled) {
            aiJobs.enqueue(
                JobSpec(
                    type = JobTypes.EMBEDDING,
                    schoolId = schoolId,
                    createdBy = currentUser.userId,
                    entityType = "LESSON",
                    entityId = lesson.id,
                    payload = mapOf("content" to lesson.summary),
                    idempotencyKey = "embed:LESSON:${lesson.id}",
                    priority = 200,
                ),
            )
        }

        return LessonSummaryDraftDto(lesson.summary.orEmpty(), mappers.terms(lesson.terms))
    }

    /* ----------------------------- Uy vazifasi ------------------------------ */

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun generateHomework(schoolId: UUID, body: HomeworkGenerateBody): HomeworkDraftDto {
        val (topicTitle, prompt) = Tx.new {
            lookups.load(schoolId)
            aiUsage.assertQuota(schoolId, currentUser.userId)
            subjects.requireInSchool(body.subjectId, schoolId, "Fan")

            val topicTitle = body.topicId?.let { topics.findInSchool(it, schoolId)?.title }
                ?: body.topic
                ?: throw ApiException.validation("Mavzu ko'rsatilmagan", mapOf("topicId" to "to'ldiring"))

            topicTitle to Prompts.homeworkGenerate(
                subjectName = lookups.subjectName(body.subjectId),
                topic = topicTitle,
                gradeLevel = body.gradeLevel,
                difficulty = body.difficulty.name,
                minutes = body.estimatedMinutes,
            )
        }

        return aiJobs.runNow(
            JobSpec(
                type = JobTypes.HOMEWORK_GENERATE,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                entityType = "SUBJECT",
                entityId = body.subjectId,
                payload = mapOf("topic" to topicTitle, "difficulty" to body.difficulty.name),
            ),
        ) {
            val (node, result) = ai.completeJson(JobTypes.HOMEWORK_GENERATE, schoolId, Prompts.SYSTEM_RULES, prompt)
            HomeworkDraftDto(
                title = node.path("title").asText(topicTitle),
                description = node.path("description").asText(""),
                estimatedMinutes = node.path("estimatedMinutes").asInt(body.estimatedMinutes),
                parentHint = node.path("parentHint").asText(null),
                terms = node.path("terms").mapNotNull { it.asText(null) },
            ) to result
        }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun generateParentHint(schoolId: UUID, homeworkId: UUID): String {
        val prompt = Tx.new {
            lookups.load(schoolId)
            aiUsage.assertQuota(schoolId, currentUser.userId)

            val hw = homeworkService.requireHomework(schoolId, homeworkId)
            access.assertOwnsOrAdmin(hw.teacherId)
            val group = lookups.group(hw.groupId)

            Prompts.parentHint(
                title = hw.title,
                description = hw.description,
                subjectName = lookups.subjectName(hw.subjectId),
                gradeLevel = group?.gradeLevel ?: 1,
            )
        }

        val hint = aiJobs.runNow(
            JobSpec(
                type = JobTypes.HOMEWORK_PARENT_HINT,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                entityType = "HOMEWORK",
                entityId = homeworkId,
            ),
        ) {
            val (node, result) = ai.completeJson(JobTypes.HOMEWORK_PARENT_HINT, schoolId, Prompts.SYSTEM_RULES, prompt)
            node.path("parentHint").asText("") to result
        }

        Tx.new {
            val hw = homeworkService.requireHomework(schoolId, homeworkId)
            hw.parentHint = hint
            hw.touch()
        }
        return hint
    }

    /**
     * Qiynalayotgan o'quvchilarni aniqlash va ular uchun soddalashtirilgan
     * variant taklif qilish. Natija SAQLANMAYDI — o'qituvchi ko'radi,
     * tanlaydi va `POST /homework/{id}/variants` orqali tasdiqlaydi.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun suggestVariants(schoolId: UUID, homeworkId: UUID): List<HomeworkVariantBody> {
        val (targets, prompt) = Tx.new {
            lookups.load(schoolId)
            aiUsage.assertQuota(schoolId, currentUser.userId)

            val hw = homeworkService.requireHomework(schoolId, homeworkId)
            access.assertOwnsOrAdmin(hw.teacherId)

            val targets = homeworkService.targetStudentIds(schoolId, hw)
            if (targets.isEmpty()) return@new targets to null

            val subjectScores = performance.academicScoresFor(schoolId, targets)
            val topicScores = targets.associateWith { studentId ->
                hw.topicId?.let { topicId ->
                    performance.topicScores(
                        schoolId, studentId, hw.subjectId,
                        java.time.LocalDate.now().minusDays(90), java.time.LocalDate.now(),
                    ).firstOrNull { it.topicId == topicId }?.score
                }
            }

            val lines = targets.joinToString("\n") { id ->
                val subjectScore = subjectScores[id] ?: 0
                val topicScore = topicScores[id] ?: subjectScore
                "$id — fan bo'yicha $subjectScore%, mavzu bo'yicha $topicScore%"
            }

            targets to Prompts.variantsSuggest(
                title = hw.title,
                description = hw.description,
                subjectName = lookups.subjectName(hw.subjectId),
                topic = hw.topicId?.let { topics.findInSchool(it, schoolId)?.title } ?: hw.title,
                groupName = lookups.groupName(hw.groupId),
                studentLines = lines,
            )
        }
        if (prompt == null) return emptyList()

        return aiJobs.runNow(
            JobSpec(
                type = JobTypes.HOMEWORK_VARIANTS,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                entityType = "HOMEWORK",
                entityId = homeworkId,
            ),
        ) {
            val (node, result) = ai.completeJson(
                JobTypes.HOMEWORK_VARIANTS, schoolId, Prompts.SYSTEM_RULES, prompt, deep = true,
            )
            val parsed = node.path("variants").mapNotNull { item ->
                val studentId = runCatching { UUID.fromString(item.path("studentId").asText()) }.getOrNull()
                    ?: return@mapNotNull null
                if (studentId !in targets) return@mapNotNull null
                HomeworkVariantBody(
                    studentId = studentId,
                    simplifiedDescription = item.path("simplifiedDescription").asText(""),
                    parentHint = item.path("parentHint").asText(null),
                    stepByStep = item.path("stepByStep").mapNotNull { it.asText(null) },
                    reason = item.path("reason").asText(null),
                )
            }
            parsed to result
        }
    }

    /* ------------------------ Ko'nikma baholash topshirig'i ------------------ */

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun generateAssessmentTask(schoolId: UUID, body: AssessmentGenerateBody): AssessmentTask {
        val teacherId = Tx.new {
            lookups.load(schoolId)
            aiUsage.assertQuota(schoolId, currentUser.userId, PlanFeatures.AI_SKILL_ANALYSIS)
            access.requireTeacher().id
        }

        val prompt = Prompts.assessmentTaskGenerate(body.gradeLevel, body.category.name)

        val node: JsonNode = aiJobs.runNow(
            JobSpec(
                type = JobTypes.ASSESSMENT_GENERATE,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                payload = mapOf("category" to body.category.name, "gradeLevel" to body.gradeLevel),
            ),
        ) {
            val (result, usage) = ai.completeJson(
                JobTypes.ASSESSMENT_GENERATE, schoolId, Prompts.SYSTEM_RULES, prompt,
            )
            result to usage
        }

        val task = AssessmentTask().apply {
            this.schoolId = schoolId
            this.teacherId = teacherId
            this.groupId = body.groupId
            this.title = node.path("title").asText("Ko'nikma topshirig'i")
            this.instruction = node.path("instruction").asText("")
            this.category = body.category
            this.expectedSignals = node.path("expectedSignals").mapNotNull { it.asText(null) }.toTypedArray()
            this.observationGuide = node.path("observationGuide").asText(null)
            this.aiGenerated = true
            this.status = AssessmentTaskStatus.DRAFT
        }
        Tx.new { tasks.persist(task) }
        return task
    }

    /** Kuzatuvlarni tahlil qilib, har bir o'quvchi uchun ko'nikma profili yozadi. */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun analyzeAssessmentTask(schoolId: UUID, taskId: UUID): AssessmentTask {
        val prompt = Tx.new {
            lookups.load(schoolId)
            aiUsage.assertQuota(schoolId, currentUser.userId, PlanFeatures.AI_SKILL_ANALYSIS)

            val task = tasks.requireInSchool(taskId, schoolId, "Topshiriq")
            access.assertOwnsOrAdmin(task.teacherId)

            val observations = Json.array(task.observations)
            if (observations.isEmpty) {
                throw ApiException.validation("Kuzatuvlar kiritilmagan — avval o'quvchilar bo'yicha qayd qo'shing")
            }

            Prompts.assessmentAnalyze(
                title = task.title,
                category = task.category.name,
                expectedSignals = task.expectedSignals.joinToString(", "),
                observations = observations.joinToString("\n") {
                    "${it.path("studentId").asText()}: ${it.path("note").asText()}"
                },
            )
        }

        val node: JsonNode = aiJobs.runNow(
            JobSpec(
                type = JobTypes.ASSESSMENT_ANALYZE,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                entityType = "ASSESSMENT_TASK",
                entityId = taskId,
            ),
        ) {
            val (result, usage) = ai.completeJson(
                JobTypes.ASSESSMENT_ANALYZE, schoolId, Prompts.SYSTEM_RULES, prompt, deep = true,
            )
            result to usage
        }

        return Tx.new {
            val task = tasks.requireInSchool(taskId, schoolId, "Topshiriq")
            node.path("students").forEach { item ->
                val studentId = runCatching { UUID.fromString(item.path("studentId").asText()) }.getOrNull()
                    ?: return@forEach
                students.findInSchool(studentId, schoolId) ?: return@forEach
                skills.persist(
                    SkillAssessment().apply {
                        this.schoolId = schoolId
                        this.studentId = studentId
                        this.teacherId = task.teacherId
                        this.taskId = task.id
                        this.category = task.category
                        this.skills = Json.write(item.path("skills"))
                        this.source = SkillSource.AI_ANALYSIS
                    },
                )
            }

            task.resultSummary = node.path("summary").asText("")
            task.status = AssessmentTaskStatus.ANALYZED
            task.touch()
            task
        }
    }

    /* --------------------------- Kognitiv tavsiyalar ------------------------ */

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    fun generateRecommendations(schoolId: UUID, studentId: UUID): List<RecommendationDto> {
        val (teacherId, prompt) = Tx.new {
            lookups.load(schoolId)
            aiUsage.assertQuota(schoolId, currentUser.userId, PlanFeatures.AI_SKILL_ANALYSIS)

            val student = students.requireInSchool(studentId, schoolId, "O'quvchi")
            student.groupId?.let { access.assertCanTouchGroup(it) }

            val perf = performance.performance(schoolId, studentId, PerformancePeriod.MONTH)
            val studentInterests = interests.listByStudent(studentId)
            val skillList = skills.listByStudent(schoolId, studentId).take(3)

            access.teacherOrNull()?.id to Prompts.recommendations(
                gradeLevel = student.gradeLevel,
                subjectScores = perf.bySubject.joinToString(", ") { "${it.subjectName} ${it.score}%" },
                weakTopics = perf.needsAttention.joinToString(", ").ifBlank { "aniqlanmagan" },
                skills = skillList.joinToString("; ") { it.skills },
                interests = studentInterests.joinToString(", ") { "${it.label} ${it.level}%" }.ifBlank { "aniqlanmagan" },
                attendanceRate = perf.attendanceRate,
            )
        }

        val node: JsonNode = aiJobs.runNow(
            JobSpec(
                type = JobTypes.RECOMMENDATIONS,
                schoolId = schoolId,
                createdBy = currentUser.userId,
                entityType = "STUDENT",
                entityId = studentId,
            ),
        ) {
            val (result, usage) = ai.completeJson(
                JobTypes.RECOMMENDATIONS, schoolId, Prompts.SYSTEM_RULES, prompt, deep = true,
            )
            result to usage
        }

        return Tx.new {
            node.path("recommendations").mapNotNull { item ->
                val entity = CognitiveRecommendation().apply {
                    this.schoolId = schoolId
                    this.studentId = studentId
                    this.teacherId = teacherId
                    this.area = runCatching { CognitiveArea.valueOf(item.path("area").asText()) }
                        .getOrDefault(CognitiveArea.LOGIC)
                    this.title = item.path("title").asText("Tavsiya")
                    this.description = item.path("description").asText("")
                    this.rationale = item.path("rationale").asText("")
                    this.steps = item.path("steps").mapNotNull { it.asText(null) }.toTypedArray()
                    this.durationMinutes = item.path("durationMinutes").asInt(15)
                    this.difficulty = runCatching { Difficulty.valueOf(item.path("difficulty").asText()) }
                        .getOrDefault(Difficulty.NORMAL)
                    this.assignedTo = runCatching { AssignedTo.valueOf(item.path("assignedTo").asText()) }
                        .getOrDefault(AssignedTo.PARENT)
                    this.createdByAi = true
                }
                recommendations.persist(entity)
                mappers.recommendation(entity)
            }
        }
    }

    private fun readTerms(node: JsonNode): List<LessonTermDto> =
        node.mapNotNull {
            val term = it.path("term").asText(null) ?: return@mapNotNull null
            LessonTermDto(term, it.path("definition").asText(""))
        }
}
