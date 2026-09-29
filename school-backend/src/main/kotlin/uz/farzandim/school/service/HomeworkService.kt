package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.HomeworkBody
import uz.farzandim.school.dto.HomeworkDto
import uz.farzandim.school.dto.HomeworkSubmissionDto
import uz.farzandim.school.dto.HomeworkVariantBody
import uz.farzandim.school.dto.HomeworkVariantDto
import uz.farzandim.school.dto.SubmissionUpdateBody
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.Homework
import uz.farzandim.shared.domain.HomeworkScope
import uz.farzandim.shared.domain.HomeworkSubmission
import uz.farzandim.shared.domain.HomeworkVariant
import uz.farzandim.shared.domain.MessageCategory
import uz.farzandim.shared.domain.SubmissionStatus
import uz.farzandim.shared.notify.NotificationService
import uz.farzandim.shared.repo.HomeworkRepository
import uz.farzandim.shared.repo.HomeworkSubmissionRepository
import uz.farzandim.shared.repo.HomeworkVariantRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.SubjectRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class HomeworkService {

    @Inject lateinit var homework: HomeworkRepository
    @Inject lateinit var variants: HomeworkVariantRepository
    @Inject lateinit var submissions: HomeworkSubmissionRepository
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var notifications: NotificationService
    @Inject lateinit var journal: JournalService
    @Inject lateinit var access: AccessControl
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var mappers: Mappers

    fun list(schoolId: UUID, scope: String?, groupId: UUID?, studentId: UUID?): List<HomeworkDto> {
        lookups.load(schoolId)
        val visible = access.visibleGroupIds()

        val today = LocalDate.now()
        val (from, to) = when (scope) {
            "today" -> today to today
            "upcoming" -> today to today.plusDays(14)
            else -> null to null
        }

        var found = when {
            groupId != null -> {
                access.assertCanTouchGroup(groupId)
                homework.listByGroup(schoolId, groupId, from, to)
            }
            from != null && to != null -> homework.list(
                "schoolId = ?1 and dueDate between ?2 and ?3 order by dueDate",
                schoolId, from, to,
            )
            else -> homework.list("schoolId = ?1 order by dueDate desc", schoolId)
        }

        if (visible != null) found = found.filter { it.groupId in visible }
        if (studentId != null) {
            val student = students.requireInSchool(studentId, schoolId, "O'quvchi")
            found = found.filter { hw ->
                hw.groupId == student.groupId &&
                    (hw.scope == HomeworkScope.GROUP || student.id in hw.targetStudentIds)
            }
        }
        return found.map { decorate(schoolId, it) }
    }

    fun get(schoolId: UUID, id: UUID): HomeworkDto {
        lookups.load(schoolId)
        val entity = requireHomework(schoolId, id)
        return decorate(schoolId, entity)
    }

    fun requireHomework(schoolId: UUID, id: UUID): Homework {
        val entity = homework.requireInSchool(id, schoolId, "Uy vazifasi")
        access.assertCanTouchGroup(entity.groupId)
        return entity
    }

    private fun decorate(schoolId: UUID, entity: Homework): HomeworkDto {
        val target = if (entity.scope == HomeworkScope.INDIVIDUAL) {
            entity.targetStudentIds.size
        } else {
            students.listByGroup(schoolId, entity.groupId).size
        }
        return mappers.homework(
            entity,
            totalStudents = target,
            doneCount = submissions.countDone(entity.id).toInt(),
            variantCount = variants.listByHomework(schoolId, entity.id).size,
        )
    }

    @Transactional
    fun create(schoolId: UUID, body: HomeworkBody, aiGenerated: Boolean = false): HomeworkDto {
        lookups.load(schoolId)
        access.assertCanTouchGroupSubject(body.groupId, body.subjectId)
        subjects.requireInSchool(body.subjectId, schoolId, "Fan")

        val teacherId = access.teacherOrNull()?.id
            ?: throw ApiException.validation("Uy vazifasini o'qituvchi yaratadi")

        if (body.scope == HomeworkScope.INDIVIDUAL && body.targetStudentIds.isEmpty()) {
            throw ApiException.validation(
                "INDIVIDUAL vazifa uchun o'quvchilar ko'rsatilishi kerak",
                mapOf("targetStudentIds" to "bo'sh"),
            )
        }

        val entity = Homework().apply {
            this.schoolId = schoolId
            this.groupId = body.groupId
            this.subjectId = body.subjectId
            this.teacherId = teacherId
            this.lessonId = body.lessonId
            this.topicId = body.topicId
            this.title = body.title.trim()
            this.description = body.description
            this.dueDate = body.dueDate
            this.estimatedMinutes = body.estimatedMinutes
            this.scope = body.scope
            this.targetStudentIds = body.targetStudentIds.toTypedArray()
            this.difficulty = body.difficulty
            this.parentHint = body.parentHint
            this.aiGenerated = aiGenerated
        }
        homework.persist(entity)
        return decorate(schoolId, entity)
    }

    @Transactional
    fun update(schoolId: UUID, id: UUID, body: HomeworkBody): HomeworkDto {
        lookups.load(schoolId)
        val entity = requireHomework(schoolId, id)
        access.assertOwnsOrAdmin(entity.teacherId)
        entity.title = body.title.trim()
        entity.description = body.description
        entity.dueDate = body.dueDate
        entity.estimatedMinutes = body.estimatedMinutes
        entity.difficulty = body.difficulty
        entity.topicId = body.topicId
        entity.parentHint = body.parentHint
        entity.scope = body.scope
        entity.targetStudentIds = body.targetStudentIds.toTypedArray()
        entity.touch()
        return decorate(schoolId, entity)
    }

    @Transactional
    fun delete(schoolId: UUID, id: UUID) {
        val entity = requireHomework(schoolId, id)
        access.assertOwnsOrAdmin(entity.teacherId)
        homework.delete(entity)
    }

    /**
     * Ota-onalarga yuborish: har bir maqsadli o'quvchi uchun topshiriq
     * yozuvi (`PENDING`) ochiladi va ota-onaga bildirishnoma ketadi.
     * Takror yuborilsa, mavjud topshiriqlar qayta yaratilmaydi.
     */
    @Transactional
    fun send(schoolId: UUID, id: UUID): HomeworkDto {
        lookups.load(schoolId)
        val entity = requireHomework(schoolId, id)
        access.assertOwnsOrAdmin(entity.teacherId)

        val targets = targetStudentIds(schoolId, entity)
        for (studentId in targets) {
            if (submissions.findForStudent(entity.id, studentId) != null) continue
            submissions.persist(
                HomeworkSubmission().apply {
                    this.schoolId = schoolId
                    this.homeworkId = entity.id
                    this.studentId = studentId
                    this.status = SubmissionStatus.PENDING
                },
            )
        }

        entity.sentToParentsAt = Instant.now()
        entity.touch()

        if (journal.settings(schoolId).notifyParentsOnHomework) {
            notifications.notifyParentsOfStudents(
                targets,
                schoolId,
                MessageCategory.HOMEWORK,
                title = "Yangi uy vazifasi",
                body = "${lookups.subjectName(entity.subjectId)}: ${entity.title}",
                link = "/homework/${entity.id}",
                payload = mapOf("homeworkId" to entity.id.toString()),
            )
        }
        return decorate(schoolId, entity)
    }

    fun targetStudentIds(schoolId: UUID, entity: Homework): List<UUID> =
        if (entity.scope == HomeworkScope.INDIVIDUAL) entity.targetStudentIds.toList()
        else students.listByGroup(schoolId, entity.groupId).map { it.id }

    /* ------------------------------- Topshiriqlar ---------------------------- */

    fun listSubmissions(schoolId: UUID, homeworkId: UUID): List<HomeworkSubmissionDto> {
        lookups.load(schoolId)
        requireHomework(schoolId, homeworkId)
        val list = submissions.listByHomework(schoolId, homeworkId)
        lookups.primeStudents(list.map { it.studentId })
        return list.map { mappers.submission(it) }
    }

    @Transactional
    fun updateSubmission(schoolId: UUID, id: UUID, body: SubmissionUpdateBody): HomeworkSubmissionDto {
        lookups.load(schoolId)
        val submission = submissions.requireInSchool(id, schoolId, "Topshiriq")
        requireHomework(schoolId, submission.homeworkId)
        body.status?.let {
            submission.status = it
            if (it == SubmissionStatus.DONE && submission.completedAt == null) {
                submission.completedAt = Instant.now()
            }
        }
        body.teacherFeedback?.let { submission.teacherFeedback = it }
        body.grade?.let { submission.gradeValue = it }
        submission.touch()
        return mappers.submission(submission)
    }

    /* --------------------------------- Variantlar ---------------------------- */

    fun listVariants(schoolId: UUID, homeworkId: UUID): List<HomeworkVariantDto> {
        lookups.load(schoolId)
        requireHomework(schoolId, homeworkId)
        val list = variants.listByHomework(schoolId, homeworkId)
        lookups.primeStudents(list.map { it.studentId })
        return list.map { mappers.variant(it) }
    }

    /** AI taklifini o'qituvchi tasdiqlagandan keyin saqlash. */
    @Transactional
    fun saveVariant(schoolId: UUID, homeworkId: UUID, body: HomeworkVariantBody, aiGenerated: Boolean): HomeworkVariantDto {
        lookups.load(schoolId)
        val entity = requireHomework(schoolId, homeworkId)
        access.assertOwnsOrAdmin(entity.teacherId)
        students.requireInSchool(body.studentId, schoolId, "O'quvchi")

        val variant = variants.findForStudent(homeworkId, body.studentId)
            ?: HomeworkVariant().apply {
                this.schoolId = schoolId
                this.homeworkId = homeworkId
                this.studentId = body.studentId
            }.also { variants.persist(it) }

        variant.simplifiedDescription = body.simplifiedDescription
        variant.parentHint = body.parentHint
        variant.stepByStep = body.stepByStep.toTypedArray()
        variant.reason = body.reason
        variant.aiGenerated = aiGenerated
        variant.approvedBy = access.teacherOrNull()?.userId

        lookups.primeStudents(listOf(body.studentId))
        return mappers.variant(variant)
    }
}
