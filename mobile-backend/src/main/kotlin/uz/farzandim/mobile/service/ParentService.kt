package uz.farzandim.mobile.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.mobile.dto.AbilityDto
import uz.farzandim.mobile.dto.AdaptedVariantDto
import uz.farzandim.mobile.dto.CharacterGroupDto
import uz.farzandim.mobile.dto.CharacterTraitDto
import uz.farzandim.mobile.dto.ChildDto
import uz.farzandim.mobile.dto.CognitiveTaskDto
import uz.farzandim.mobile.dto.DashboardDto
import uz.farzandim.mobile.dto.DayTaskDto
import uz.farzandim.mobile.dto.DeviceBody
import uz.farzandim.mobile.dto.HomeworkDto
import uz.farzandim.mobile.dto.InterestDto
import uz.farzandim.mobile.dto.LessonSummaryDto
import uz.farzandim.mobile.dto.LessonTermDto
import uz.farzandim.mobile.dto.MessageDto
import uz.farzandim.mobile.dto.MessageLinkDto
import uz.farzandim.mobile.dto.ProgressTrendDto
import uz.farzandim.mobile.dto.RecentGradeDto
import uz.farzandim.mobile.dto.ScheduleItemDto
import uz.farzandim.mobile.dto.SchoolBriefDto
import uz.farzandim.mobile.dto.StrengthItemDto
import uz.farzandim.mobile.dto.StrengthWeaknessDto
import uz.farzandim.mobile.dto.SubjectDetailDto
import uz.farzandim.mobile.dto.SubjectScoreDto
import uz.farzandim.mobile.dto.TeacherDto
import uz.farzandim.mobile.dto.TopicScoreDto
import uz.farzandim.mobile.dto.TrendPointDto
import uz.farzandim.shared.analytics.PerformanceService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.AttendanceStatus
import uz.farzandim.shared.domain.DevicePlatform
import uz.farzandim.shared.domain.DeviceToken
import uz.farzandim.shared.domain.HomeworkScope
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.domain.RecommendationStatus
import uz.farzandim.shared.domain.Student
import uz.farzandim.shared.domain.SubmissionStatus
import uz.farzandim.shared.domain.SummaryStatus
import uz.farzandim.shared.repo.AttendanceRepository
import uz.farzandim.shared.repo.DeviceTokenRepository
import uz.farzandim.shared.repo.GradeRepository
import uz.farzandim.shared.repo.GroupRepository
import uz.farzandim.shared.repo.HomeworkRepository
import uz.farzandim.shared.repo.HomeworkSubmissionRepository
import uz.farzandim.shared.repo.HomeworkVariantRepository
import uz.farzandim.shared.repo.InterestRepository
import uz.farzandim.shared.repo.LessonRepository
import uz.farzandim.shared.repo.NotificationRepository
import uz.farzandim.shared.repo.RecommendationRepository
import uz.farzandim.shared.repo.ScheduleSlotRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.SkillAssessmentRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.repo.TeacherAssignmentRepository
import uz.farzandim.shared.repo.TeacherRepository
import uz.farzandim.shared.repo.TopicRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.util.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Ota-ona uchun ma'lumotlar.
 *
 * Har bir metod avval `ParentAccess.requireChild()` orqali o'tadi —
 * farzand ota-onaga tegishli ekanligi bazadan tasdiqlanadi.
 */
@ApplicationScoped
class ParentService {

    @Inject lateinit var access: ParentAccess
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var performance: PerformanceService

    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var topics: TopicRepository
    @Inject lateinit var teachers: TeacherRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assignments: TeacherAssignmentRepository
    @Inject lateinit var slots: ScheduleSlotRepository
    @Inject lateinit var lessons: LessonRepository
    @Inject lateinit var grades: GradeRepository
    @Inject lateinit var attendance: AttendanceRepository
    @Inject lateinit var homework: HomeworkRepository
    @Inject lateinit var submissions: HomeworkSubmissionRepository
    @Inject lateinit var variants: HomeworkVariantRepository
    @Inject lateinit var interests: InterestRepository
    @Inject lateinit var skills: SkillAssessmentRepository
    @Inject lateinit var recommendations: RecommendationRepository
    @Inject lateinit var notifications: NotificationRepository
    @Inject lateinit var devices: DeviceTokenRepository

    /* -------------------------------- Farzandlar ---------------------------- */

    fun children(): List<ChildDto> = access.childIds().mapNotNull { id ->
        runCatching { toChild(access.requireChild(id)) }.getOrNull()
    }

    private fun toChild(student: Student): ChildDto {
        val schoolId = student.schoolId
        val perf = performance.performance(schoolId, student.id, PerformancePeriod.MONTH)
        val today = attendance.listByStudent(schoolId, student.id, LocalDate.now(), LocalDate.now())

        return ChildDto(
            id = student.id,
            firstName = student.firstName,
            lastName = student.lastName,
            avatarUrl = student.avatarUrl,
            gradeLevel = student.gradeLevel,
            groupName = student.groupId?.let { groups.findById(it)?.name } ?: "—",
            birthDate = student.birthDate,
            status = student.status.name,
            atSchool = today.any { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE },
            academicScore = perf.academicScore,
            academicDelta = perf.scoreDelta,
            activityScore = perf.skillScore,
            activityDelta = 0,
            attendanceRate = perf.attendanceRate,
            attendanceDelta = 0,
            interestsCount = interests.listByStudent(student.id).size,
            weaknessesCount = perf.needsAttention.size,
        )
    }

    fun dashboard(childId: UUID): DashboardDto {
        val child = access.requireChild(childId)
        val schoolId = child.schoolId
        val today = LocalDate.now()

        val todayLessons = child.groupId
            ?.let { lessons.listByGroupAndRange(schoolId, it, today, today) }
            .orEmpty()

        val dueHomework = child.groupId
            ?.let { homework.listByGroup(schoolId, it, today, today.plusDays(7)) }
            .orEmpty()
            .filter { it.sentToParentsAt != null && isTargeted(it.scope, it.targetStudentIds, childId) }

        val submissionMap = submissions.listForStudent(schoolId, childId).associateBy { it.homeworkId }

        val tasks = todayLessons.map { lesson ->
            DayTaskDto(
                id = lesson.id,
                kind = "LESSON",
                subjectId = lesson.subjectId,
                subjectName = subjectName(lesson.subjectId),
                colorKey = colorKey(lesson.subjectId),
                time = lesson.startTime.toString(),
                label = lesson.title.ifBlank { subjectName(lesson.subjectId) },
                done = lesson.status.name == "DONE",
            )
        } + dueHomework.map { hw ->
            DayTaskDto(
                id = hw.id,
                kind = "HOMEWORK",
                subjectId = hw.subjectId,
                subjectName = subjectName(hw.subjectId),
                colorKey = colorKey(hw.subjectId),
                time = hw.dueDate.toString(),
                label = hw.title,
                done = submissionMap[hw.id]?.status == SubmissionStatus.DONE,
            )
        }

        val lastLesson = child.groupId
            ?.let { lessons.lastSentSummary(schoolId, it) }
            ?.let { toLessonSummary(it) }

        return DashboardDto(
            child = toChild(child),
            tasks = tasks.sortedBy { it.time },
            lastLesson = lastLesson,
            unreadMessages = notifications.countUnread(currentUser.userId),
        )
    }

    /* ------------------------------- Fanlar --------------------------------- */

    fun subjectScores(childId: UUID): List<SubjectScoreDto> {
        val child = access.requireChild(childId)
        val (from, to) = performance.rangeFor(PerformancePeriod.SEMESTER)
        val teacherBySubject = child.groupId?.let { groupId ->
            assignments.listByGroup(child.schoolId, groupId).associate { it.subjectId to it.teacherId }
        }.orEmpty()

        return performance.subjectScores(child.schoolId, childId, from, to).map {
            SubjectScoreDto(
                subjectId = it.subjectId,
                name = it.subjectName,
                shortName = it.shortName,
                colorKey = it.colorKey,
                score = it.score,
                delta = it.delta,
                teacherName = teacherName(teacherBySubject[it.subjectId]),
            )
        }
    }

    fun subjectDetail(childId: UUID, subjectId: UUID): SubjectDetailDto {
        val child = access.requireChild(childId)
        val base = subjectScores(childId).firstOrNull { it.subjectId == subjectId }
            ?: throw ApiException.notFound("Fan", subjectId)

        val (from, to) = performance.rangeFor(PerformancePeriod.SEMESTER)
        val byTopic = performance.topicScores(child.schoolId, childId, subjectId, from, to)
        val recent = grades.listByStudentAndSubject(child.schoolId, childId, subjectId).take(10)

        return SubjectDetailDto(
            subjectId = base.subjectId,
            name = base.name,
            shortName = base.shortName,
            colorKey = base.colorKey,
            score = base.score,
            delta = base.delta,
            teacherName = base.teacherName,
            byTopic = byTopic.map { TopicScoreDto(it.topicId, it.title, it.score) },
            strengths = byTopic.filter { it.score >= PerformanceService.STRENGTH_THRESHOLD }.map { it.title },
            needsWork = byTopic.filter { it.score < PerformanceService.DEVELOPING_THRESHOLD }.map { it.title },
            recentGrades = recent.map {
                RecentGradeDto(it.id, it.gradedOn, it.type.name, it.value, it.topicId?.let { t -> topicTitle(t) })
            },
            trend = performance.trend(child.schoolId, childId, PerformancePeriod.MONTH)
                .map { TrendPointDto(it.label, it.value) },
            teacherComment = recent.firstNotNullOfOrNull { it.comment },
        )
    }

    fun progress(childId: UUID, period: String): ProgressTrendDto {
        val child = access.requireChild(childId)
        val resolved = runCatching { PerformancePeriod.valueOf(period) }.getOrDefault(PerformancePeriod.MONTH)
        val points = performance.trend(child.schoolId, childId, resolved).map { TrendPointDto(it.label, it.value) }
        val perf = performance.performance(child.schoolId, childId, resolved)
        val (from, to) = performance.rangeFor(resolved)

        return ProgressTrendDto(
            period = resolved.name,
            rangeLabel = "$from — $to",
            points = points,
            average = if (points.isEmpty()) perf.academicScore else points.map { it.value }.average().roundToInt(),
            delta = perf.scoreDelta,
        )
    }

    /* --------------------- Xarakter, kuchli/zaif, qiziqish ------------------ */

    fun character(childId: UUID): List<CharacterGroupDto> {
        val child = access.requireChild(childId)
        val assessments = skills.listByStudent(child.schoolId, childId)
        if (assessments.isEmpty()) return emptyList()

        return assessments.groupBy { it.category }.map { (category, list) ->
            val latest = list.maxByOrNull { it.assessedOn }
            val traits = Json.array(latest?.skills).mapNotNull { node ->
                val key = node.path("key").asText(null) ?: return@mapNotNull null
                CharacterTraitDto(
                    key = key,
                    label = node.path("label").asText(key),
                    score = node.path("score").asInt(0),
                    description = node.path("note").asText(""),
                )
            }
            CharacterGroupDto(
                category = category.name,
                label = categoryLabel(category.name),
                colorKey = categoryColor(category.name),
                score = if (traits.isEmpty()) 0 else traits.map { it.score }.average().roundToInt(),
                traits = traits,
                summary = latest?.summary.orEmpty(),
                updatedAt = latest?.createdAt,
                source = latest?.source?.name ?: "TEACHER",
            )
        }
    }

    fun strengths(childId: UUID): StrengthWeaknessDto {
        val child = access.requireChild(childId)
        val perf = performance.performance(child.schoolId, childId, PerformancePeriod.SEMESTER)

        return StrengthWeaknessDto(
            strengths = perf.strengths.map {
                StrengthItemDto(it, "Bu mavzuda o'zlashtirish 85% dan yuqori", "green")
            },
            weaknesses = perf.needsAttention.map {
                StrengthItemDto(it, "Bu mavzuda qo'shimcha mashq foydali bo'ladi", "amber")
            },
            summary = buildString {
                append("Umumiy o'zlashtirish ${perf.academicScore}%, davomat ${perf.attendanceRate}%. ")
                if (perf.strengths.isNotEmpty()) append("Kuchli tomonlari: ${perf.strengths.take(3).joinToString(", ")}. ")
                if (perf.needsAttention.isNotEmpty()) {
                    append("E'tibor talab qiladigan mavzular: ${perf.needsAttention.take(3).joinToString(", ")}.")
                }
            },
        )
    }

    fun interests(childId: UUID): List<InterestDto> {
        access.requireChild(childId)
        return interests.listByStudent(childId).map {
            InterestDto(it.id, it.key, it.label, it.description.orEmpty(), it.level, it.colorKey, it.iconKey, it.source.name)
        }
    }

    /** Ko'nikma baholaridan tuzilgan "qobiliyatlar" ro'yxati. */
    fun abilities(childId: UUID): List<AbilityDto> {
        val child = access.requireChild(childId)
        return skills.listByStudent(child.schoolId, childId)
            .flatMap { assessment ->
                Json.array(assessment.skills).mapNotNull { node ->
                    val key = node.path("key").asText(null) ?: return@mapNotNull null
                    AbilityDto(
                        id = UUID.nameUUIDFromBytes("${assessment.id}:$key".toByteArray()),
                        label = node.path("label").asText(key),
                        description = node.path("note").asText(""),
                        level = node.path("score").asInt(0),
                        colorKey = categoryColor(assessment.category.name),
                        iconKey = "sparkles",
                    )
                }
            }
            .distinctBy { it.label }
            .sortedByDescending { it.level }
    }

    /* ------------------------------ Uy vazifalari --------------------------- */

    fun homeworkList(childId: UUID, scope: String?): List<HomeworkDto> {
        val child = access.requireChild(childId)
        val groupId = child.groupId ?: return emptyList()
        val today = LocalDate.now()

        val (from, to) = when (scope) {
            "today" -> today to today
            "upcoming" -> today to today.plusDays(14)
            else -> today.minusDays(30) to today.plusDays(30)
        }

        val submissionMap = submissions.listForStudent(child.schoolId, childId).associateBy { it.homeworkId }
        val variantMap = variants.list("studentId = ?1", childId).associateBy { it.homeworkId }

        return homework.listByGroup(child.schoolId, groupId, from, to)
            .filter { it.sentToParentsAt != null && isTargeted(it.scope, it.targetStudentIds, childId) }
            .map { hw ->
                val submission = submissionMap[hw.id]
                val variant = variantMap[hw.id]
                HomeworkDto(
                    id = hw.id,
                    subjectId = hw.subjectId,
                    subjectName = subjectName(hw.subjectId),
                    colorKey = colorKey(hw.subjectId),
                    title = hw.title,
                    description = hw.description,
                    dueDate = hw.dueDate,
                    estimatedMinutes = hw.estimatedMinutes,
                    difficulty = hw.difficulty,
                    status = when {
                        submission?.status == SubmissionStatus.DONE -> "DONE"
                        hw.dueDate.isBefore(today) -> "MISSED"
                        else -> "PENDING"
                    },
                    teacherName = teacherName(hw.teacherId),
                    parentHint = hw.parentHint,
                    adaptedVariant = variant?.let {
                        AdaptedVariantDto(
                            reason = it.reason.orEmpty(),
                            description = it.simplifiedDescription,
                            stepByStep = it.stepByStep.toList(),
                            parentHint = it.parentHint.orEmpty(),
                        )
                    },
                )
            }
            .sortedBy { it.dueDate }
    }

    fun homeworkDetail(homeworkId: UUID): HomeworkDto {
        // Vazifa ota-onaning qaysidir farzandiga tegishli bo'lishi shart.
        for (childId in access.childIds()) {
            homeworkList(childId, "all").firstOrNull { it.id == homeworkId }?.let { return it }
        }
        throw ApiException.notFound("Uy vazifasi", homeworkId)
    }

    @Transactional
    fun markHomework(homeworkId: UUID, childId: UUID, status: String): HomeworkDto {
        val child = access.requireChild(childId)
        val hw = homework.findInSchool(homeworkId, child.schoolId)
            ?: throw ApiException.notFound("Uy vazifasi", homeworkId)
        if (hw.groupId != child.groupId || !isTargeted(hw.scope, hw.targetStudentIds, childId)) {
            throw ApiException.notFound("Uy vazifasi", homeworkId)
        }

        val submission = submissions.findForStudent(homeworkId, childId)
            ?: throw ApiException.notFound("Topshiriq", homeworkId)

        submission.status = runCatching { SubmissionStatus.valueOf(status) }.getOrDefault(SubmissionStatus.DONE)
        submission.parentConfirmed = submission.status == SubmissionStatus.DONE
        submission.confirmedBy = currentUser.userId
        submission.completedAt = if (submission.status == SubmissionStatus.DONE) Instant.now() else null
        submission.touch()

        return homeworkList(childId, "all").first { it.id == homeworkId }
    }

    /* --------------------------------- Darslar ------------------------------ */

    fun lesson(lessonId: UUID): LessonSummaryDto {
        val childSchools = access.childIds().mapNotNull { runCatching { access.requireChild(it) }.getOrNull() }
        for (child in childSchools) {
            val lesson = lessons.findInSchool(lessonId, child.schoolId) ?: continue
            if (lesson.groupId != child.groupId) continue
            if (lesson.summaryStatus != SummaryStatus.SENT_TO_PARENTS) continue
            return toLessonSummary(lesson)
        }
        throw ApiException.notFound("Dars", lessonId)
    }

    private fun toLessonSummary(lesson: uz.farzandim.shared.domain.Lesson) = LessonSummaryDto(
        id = lesson.id,
        subjectId = lesson.subjectId,
        subjectName = subjectName(lesson.subjectId),
        colorKey = colorKey(lesson.subjectId),
        date = lesson.lessonDate,
        topic = lesson.topicId?.let { topicTitle(it) } ?: lesson.title,
        teacherName = teacherName(lesson.teacherId),
        summary = lesson.summary.orEmpty(),
        terms = Json.read(lesson.terms, emptyList<LessonTermDto>()),
    )

    /* ------------------------------- Tavsiyalar ----------------------------- */

    fun recommendations(childId: UUID): List<CognitiveTaskDto> {
        access.requireChild(childId)
        return recommendations.listByStudent(childId).map { rec ->
            CognitiveTaskDto(
                id = rec.id,
                area = rec.area,
                areaLabel = areaLabel(rec.area.name),
                colorKey = areaColor(rec.area.name),
                title = rec.title,
                description = rec.description,
                rationale = rec.rationale,
                steps = rec.steps.toList(),
                durationMinutes = rec.durationMinutes,
                difficulty = rec.difficulty,
                status = rec.status.name,
                withParent = rec.assignedTo.name == "PARENT",
                createdAt = rec.createdAt,
            )
        }
    }

    @Transactional
    fun updateRecommendation(id: UUID, status: String): CognitiveTaskDto {
        val rec = recommendations.findScoped(id, access.childIds())
            ?: throw ApiException.notFound("Tavsiya", id)
        rec.status = runCatching { RecommendationStatus.valueOf(status) }
            .getOrDefault(RecommendationStatus.IN_PROGRESS)
        rec.touch()
        return recommendations(rec.studentId).first { it.id == id }
    }

    /* --------------------------- Maktab ma'lumotlari ------------------------ */

    fun school(): SchoolBriefDto {
        access.assertSchoolBound()
        val schoolId = access.profile().schoolId ?: throw ApiException.notFound("Maktab")
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        return SchoolBriefDto(school.id, school.name, school.address, school.logoUrl, school.phone, school.academicYear)
    }

    fun teachers(): List<TeacherDto> {
        access.assertSchoolBound()
        val childGroups = access.childIds()
            .mapNotNull { runCatching { access.requireChild(it) }.getOrNull() }
            .mapNotNull { it.groupId }
            .toSet()
        if (childGroups.isEmpty()) return emptyList()

        val schoolId = access.profile().schoolId ?: return emptyList()
        val relevant = assignments.listInSchool(schoolId).filter { it.groupId in childGroups }
        val teacherMap = teachers.findAllByIds(relevant.map { it.teacherId }.distinct()).associateBy { it.id }
        val userMap = users.findAllByIds(teacherMap.values.map { it.userId }).associateBy { it.id }

        return relevant.groupBy { it.teacherId }.mapNotNull { (teacherId, list) ->
            val teacher = teacherMap[teacherId] ?: return@mapNotNull null
            val user = userMap[teacher.userId] ?: return@mapNotNull null
            val subjectId = list.first().subjectId
            TeacherDto(
                id = teacher.id,
                firstName = user.firstName,
                lastName = user.lastName,
                avatarUrl = user.avatarUrl,
                subjectName = subjectName(subjectId),
                colorKey = colorKey(subjectId),
                groups = list.mapNotNull { groups.findById(it.groupId)?.name }.distinct(),
                phone = user.phone,
                email = user.email,
            )
        }
    }

    fun schedule(childId: UUID?): List<ScheduleItemDto> {
        access.assertSchoolBound()
        val child = childId?.let { access.requireChild(it) }
            ?: access.childIds().firstNotNullOfOrNull { runCatching { access.requireChild(it) }.getOrNull() }
            ?: return emptyList()
        val groupId = child.groupId ?: return emptyList()

        return slots.listByGroup(child.schoolId, groupId).map {
            ScheduleItemDto(
                id = it.id,
                dayOfWeek = it.dayOfWeek,
                startTime = it.startTime,
                endTime = it.endTime,
                subjectId = it.subjectId,
                subjectName = subjectName(it.subjectId),
                colorKey = colorKey(it.subjectId),
                teacherName = teacherName(it.teacherId),
                room = it.room,
            )
        }
    }

    /* --------------------------------- Xabarlar ----------------------------- */

    fun messages(limit: Int): List<MessageDto> =
        notifications.listForUser(currentUser.userId, limit).map {
            MessageDto(
                id = it.id,
                category = it.type,
                colorKey = it.colorKey,
                iconKey = it.iconKey,
                title = it.title,
                body = it.body,
                sentAt = it.createdAt,
                isRead = it.isRead,
                link = linkOf(it.payload, it.link),
            )
        }

    @Transactional
    fun markMessageRead(id: UUID) {
        if (notifications.markRead(id, currentUser.userId) == 0) {
            throw ApiException.notFound("Xabar", id)
        }
    }

    @Transactional
    fun registerDevice(body: DeviceBody) {
        val existing = devices.findByToken(body.token)
        val platform = runCatching { DevicePlatform.valueOf(body.platform.uppercase()) }
            .getOrDefault(DevicePlatform.ANDROID)

        if (existing != null) {
            existing.userId = currentUser.userId
            existing.platform = platform
            existing.locale = body.locale
            existing.appVersion = body.appVersion
            existing.lastSeenAt = Instant.now()
            return
        }
        devices.persist(
            DeviceToken().apply {
                this.userId = currentUser.userId
                this.token = body.token
                this.platform = platform
                this.locale = body.locale
                this.appVersion = body.appVersion
            },
        )
    }

    @Transactional
    fun unregisterDevice(token: String) {
        devices.deleteByToken(token)
    }

    /* ------------------------------- Yordamchilar --------------------------- */

    private fun isTargeted(scope: HomeworkScope, targets: Array<UUID>, childId: UUID): Boolean =
        scope == HomeworkScope.GROUP || childId in targets

    private fun subjectName(id: UUID?): String = id?.let { subjects.findById(it)?.name } ?: "—"
    private fun colorKey(id: UUID?): String = id?.let { subjects.findById(it)?.colorKey } ?: "blue"
    private fun topicTitle(id: UUID): String? = topics.findById(id)?.title

    private fun teacherName(id: UUID?): String {
        val teacher = id?.let { teachers.findById(it) } ?: return "—"
        val user = users.findById(teacher.userId) ?: return "—"
        return "${user.lastName} ${user.firstName}"
    }

    private fun linkOf(payload: String, link: String?): MessageLinkDto? {
        val node = Json.obj(payload)
        listOf("lessonId" to "lesson", "homeworkId" to "homework", "subjectId" to "subject").forEach { (key, screen) ->
            node.path(key).asText(null)?.let { raw ->
                runCatching { return MessageLinkDto(screen, UUID.fromString(raw)) }
            }
        }
        return null
    }

    private fun categoryLabel(category: String) = when (category) {
        "COGNITIVE" -> "Intellekt"
        "BEHAVIOR" -> "Xulq-atvor"
        "SOCIAL" -> "Ijtimoiy ko'nikma"
        "CREATIVE" -> "Ijodkorlik"
        else -> "Qiziqish"
    }

    private fun categoryColor(category: String) = when (category) {
        "COGNITIVE" -> "blue"
        "BEHAVIOR" -> "amber"
        "SOCIAL" -> "teal"
        "CREATIVE" -> "purple"
        else -> "pink"
    }

    private fun areaLabel(area: String) = when (area) {
        "LOGIC" -> "Mantiq"
        "MEMORY" -> "Xotira"
        "ATTENTION" -> "Diqqat"
        "LANGUAGE" -> "Til"
        "SPATIAL" -> "Fazoviy tasavvur"
        "CREATIVITY" -> "Ijodkorlik"
        else -> "Hissiy intellekt"
    }

    private fun areaColor(area: String) = when (area) {
        "LOGIC" -> "blue"
        "MEMORY" -> "purple"
        "ATTENTION" -> "amber"
        "LANGUAGE" -> "teal"
        "SPATIAL" -> "green"
        "CREATIVITY" -> "pink"
        else -> "blue"
    }
}
