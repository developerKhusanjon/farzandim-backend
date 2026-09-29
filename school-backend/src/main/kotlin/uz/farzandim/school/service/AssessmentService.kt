package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.AchievementDto
import uz.farzandim.school.dto.AssessmentTaskBody
import uz.farzandim.school.dto.AssessmentTaskDto
import uz.farzandim.school.dto.InterestBody
import uz.farzandim.school.dto.InterestDto
import uz.farzandim.school.dto.ObservationsBody
import uz.farzandim.school.dto.RecommendationDto
import uz.farzandim.school.dto.SkillAssessmentDto
import uz.farzandim.school.dto.StudentPerformanceDto
import uz.farzandim.school.dto.SubjectDetailDto
import uz.farzandim.shared.analytics.PerformanceService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.AssessmentTask
import uz.farzandim.shared.domain.AssessmentTaskStatus
import uz.farzandim.shared.domain.Interest
import uz.farzandim.shared.domain.InterestSource
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.domain.RecommendationStatus
import uz.farzandim.shared.repo.AchievementRepository
import uz.farzandim.shared.repo.AssessmentTaskRepository
import uz.farzandim.shared.repo.GradeRepository
import uz.farzandim.shared.repo.InterestRepository
import uz.farzandim.shared.repo.RecommendationRepository
import uz.farzandim.shared.repo.SkillAssessmentRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.util.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * O'quvchi profili: o'zlashtirish, ko'nikma, qiziqish, tavsiya.
 * Ya'ni "bola qanday o'qiyapti" degan savolga javob beradigan hamma narsa.
 */
@ApplicationScoped
class AssessmentService {

    @Inject lateinit var tasks: AssessmentTaskRepository
    @Inject lateinit var skills: SkillAssessmentRepository
    @Inject lateinit var interests: InterestRepository
    @Inject lateinit var recommendations: RecommendationRepository
    @Inject lateinit var achievements: AchievementRepository
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var grades: GradeRepository
    @Inject lateinit var performance: PerformanceService
    @Inject lateinit var access: AccessControl
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var mappers: Mappers

    private fun requireStudent(schoolId: UUID, studentId: UUID) {
        val student = students.requireInSchool(studentId, schoolId, "O'quvchi")
        student.groupId?.let { access.assertCanTouchGroup(it) }
    }

    /* ------------------------------ O'zlashtirish --------------------------- */

    fun performance(schoolId: UUID, studentId: UUID, period: PerformancePeriod): StudentPerformanceDto {
        requireStudent(schoolId, studentId)
        val data = performance.performance(schoolId, studentId, period)
        return StudentPerformanceDto(
            studentId = data.studentId,
            period = data.period,
            academicScore = data.academicScore,
            skillScore = data.skillScore,
            knowledgeScore = data.knowledgeScore,
            attendanceRate = data.attendanceRate,
            scoreDelta = data.scoreDelta,
            bySubject = data.bySubject,
            strengths = data.strengths,
            developing = data.developing,
            needsAttention = data.needsAttention,
            trend = data.trend,
        )
    }

    fun subjectDetail(schoolId: UUID, studentId: UUID, subjectId: UUID): SubjectDetailDto {
        lookups.load(schoolId)
        requireStudent(schoolId, studentId)
        subjects.requireInSchool(subjectId, schoolId, "Fan")

        val (from, to) = performance.rangeFor(PerformancePeriod.SEMESTER)
        val byTopic = performance.topicScores(schoolId, studentId, subjectId, from, to)
        val subjectScores = performance.subjectScores(schoolId, studentId, from, to)
        val own = subjectScores.firstOrNull { it.subjectId == subjectId }
        val recent = grades.listByStudentAndSubject(schoolId, studentId, subjectId).take(10)

        return SubjectDetailDto(
            subjectId = subjectId,
            subjectName = lookups.subjectName(subjectId),
            colorKey = lookups.colorKey(subjectId),
            score = own?.score ?: 0,
            delta = own?.delta ?: 0,
            strengths = byTopic.filter { it.score >= PerformanceService.STRENGTH_THRESHOLD }.map { it.title },
            developing = byTopic.filter {
                it.score in PerformanceService.DEVELOPING_THRESHOLD until PerformanceService.STRENGTH_THRESHOLD
            }.map { it.title },
            needsAttention = byTopic.filter { it.score < PerformanceService.DEVELOPING_THRESHOLD }.map { it.title },
            byTopic = byTopic,
            recentGrades = recent.map { mappers.grade(it) },
            trend = performance.trend(schoolId, studentId, PerformancePeriod.MONTH),
        )
    }

    /* -------------------------------- Ko'nikma ------------------------------ */

    fun listSkills(schoolId: UUID, studentId: UUID): List<SkillAssessmentDto> {
        lookups.load(schoolId)
        requireStudent(schoolId, studentId)
        return skills.listByStudent(schoolId, studentId).map { mappers.skillAssessment(it) }
    }

    fun listAchievements(schoolId: UUID, studentId: UUID): List<AchievementDto> {
        requireStudent(schoolId, studentId)
        return achievements.listByStudent(studentId).map { mappers.achievement(it) }
    }

    /* -------------------------------- Qiziqish ------------------------------ */

    fun listInterests(schoolId: UUID, studentId: UUID): List<InterestDto> {
        requireStudent(schoolId, studentId)
        return interests.listByStudent(studentId).map { mappers.interest(it) }
    }

    @Transactional
    fun upsertInterest(schoolId: UUID, studentId: UUID, body: InterestBody): InterestDto {
        requireStudent(schoolId, studentId)
        val entity = interests.findByKey(studentId, body.key) ?: Interest().apply {
            this.schoolId = schoolId
            this.studentId = studentId
            this.key = body.key
        }.also { interests.persist(it) }

        entity.label = body.label
        entity.description = body.description
        entity.level = body.level.coerceIn(0, 100)
        entity.colorKey = body.colorKey
        entity.iconKey = body.iconKey
        entity.source = if (access.isTeacher) InterestSource.TEACHER else InterestSource.AI
        entity.updatedAt = Instant.now()
        return mappers.interest(entity)
    }

    /* --------------------------- Baholash topshiriqlari --------------------- */

    fun listTasks(schoolId: UUID, groupId: UUID?): List<AssessmentTaskDto> {
        lookups.load(schoolId)
        var found = tasks.listInSchool(schoolId)
        if (groupId != null) {
            access.assertCanTouchGroup(groupId)
            found = found.filter { it.groupId == groupId }
        }
        access.teacherOrNull()?.let { teacher ->
            found = found.filter { it.teacherId == teacher.id }
        }
        return found.sortedByDescending { it.createdAt }.map { mappers.assessmentTask(it) }
    }

    @Transactional
    fun createTask(schoolId: UUID, body: AssessmentTaskBody): AssessmentTaskDto {
        lookups.load(schoolId)
        val teacher = access.requireTeacher()
        body.groupId?.let { access.assertCanTouchGroup(it) }

        val entity = AssessmentTask().apply {
            this.schoolId = schoolId
            this.teacherId = teacher.id
            this.groupId = body.groupId
            this.studentIds = body.studentIds.toTypedArray()
            this.title = body.title.trim()
            this.instruction = body.instruction
            this.category = body.category
            this.expectedSignals = body.expectedSignals.toTypedArray()
            this.observationGuide = body.observationGuide
            this.dueDate = body.dueDate
            this.status = AssessmentTaskStatus.ASSIGNED
        }
        tasks.persist(entity)
        return mappers.assessmentTask(entity)
    }

    @Transactional
    fun addObservations(schoolId: UUID, taskId: UUID, body: ObservationsBody): AssessmentTaskDto {
        lookups.load(schoolId)
        val task = tasks.requireInSchool(taskId, schoolId, "Topshiriq")
        access.assertOwnsOrAdmin(task.teacherId)

        val existing = Json.array(task.observations)
        body.observations.forEach { observation ->
            students.requireInSchool(observation.studentId, schoolId, "O'quvchi")
            existing.add(
                Json.mapper.createObjectNode()
                    .put("studentId", observation.studentId.toString())
                    .put("note", observation.note),
            )
        }
        task.observations = Json.write(existing)
        task.status = AssessmentTaskStatus.COLLECTED
        task.touch()
        return mappers.assessmentTask(task)
    }

    /* -------------------------------- Tavsiyalar ---------------------------- */

    fun listRecommendations(schoolId: UUID, studentId: UUID): List<RecommendationDto> {
        lookups.load(schoolId)
        requireStudent(schoolId, studentId)
        return recommendations.listByStudent(studentId).map { mappers.recommendation(it) }
    }

    @Transactional
    fun updateRecommendationStatus(schoolId: UUID, id: UUID, status: RecommendationStatus): RecommendationDto {
        lookups.load(schoolId)
        val entity = recommendations.findById(id) ?: throw ApiException.notFound("Tavsiya", id)
        if (entity.schoolId != schoolId) throw ApiException.notFound("Tavsiya", id)
        requireStudent(schoolId, entity.studentId)
        entity.status = status
        entity.touch()
        return mappers.recommendation(entity)
    }
}
