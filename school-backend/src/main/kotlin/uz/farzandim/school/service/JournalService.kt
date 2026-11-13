package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.AttendanceBody
import uz.farzandim.school.dto.AttendanceDto
import uz.farzandim.school.dto.GradeBody
import uz.farzandim.school.dto.GradeDto
import uz.farzandim.school.dto.GradebookCellDto
import uz.farzandim.school.dto.GradebookDto
import uz.farzandim.school.dto.GradebookStudentDto
import uz.farzandim.school.dto.SchoolSettingsDto
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.Attendance
import uz.farzandim.shared.domain.AttendanceStatus
import uz.farzandim.shared.domain.Grade
import uz.farzandim.shared.domain.MessageCategory
import uz.farzandim.shared.notify.NotificationService
import uz.farzandim.shared.repo.AttendanceRepository
import uz.farzandim.shared.repo.GradeRepository
import uz.farzandim.shared.repo.LessonRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.repo.TeacherAssignmentRepository
import uz.farzandim.shared.util.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Jurnal: baho va davomat.
 *
 * Har ikkalasi ham ota-onaga xabar yuborishi mumkin — lekin faqat
 * maktab sozlamasida yoqilgan bo'lsa (`notifyParentsOnGrade`,
 * `notifyParentsOnAbsence`). Bu qaror maktabniki, bizniki emas.
 */
@ApplicationScoped
class JournalService {

    @Inject lateinit var grades: GradeRepository
    @Inject lateinit var attendance: AttendanceRepository
    @Inject lateinit var lessons: LessonRepository
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var assignments: TeacherAssignmentRepository
    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var notifications: NotificationService
    @Inject lateinit var access: AccessControl
    @Inject lateinit var schedule: ScheduleService
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var mappers: Mappers

    /* ---------------------------------- Baho -------------------------------- */

    fun listGrades(schoolId: UUID, studentId: UUID, from: LocalDate?, to: LocalDate?): List<GradeDto> {
        lookups.load(schoolId)
        val student = students.requireInSchool(studentId, schoolId, "O'quvchi")
        student.groupId?.let { access.assertCanTouchGroup(it) }
        return grades.listByStudent(schoolId, studentId, from, to).map { mappers.grade(it) }
    }

    @Transactional
    fun createGrade(schoolId: UUID, body: GradeBody): GradeDto {
        lookups.load(schoolId)
        val dto = persistGrade(schoolId, body)
        notifyGrades(schoolId, listOf(body.studentId), 1)
        return dto
    }

    @Transactional
    fun createGradesBulk(schoolId: UUID, bodies: List<GradeBody>): List<GradeDto> {
        lookups.load(schoolId)
        if (bodies.isEmpty()) return emptyList()
        val created = bodies.map { persistGrade(schoolId, it) }
        notifyGrades(schoolId, bodies.map { it.studentId }.distinct(), created.size)
        return created
    }

    private fun persistGrade(schoolId: UUID, body: GradeBody): GradeDto {
        val student = students.requireInSchool(body.studentId, schoolId, "O'quvchi")
        subjects.requireInSchool(body.subjectId, schoolId, "Fan")
        val groupId = student.groupId ?: throw ApiException.validation("O'quvchi guruhga biriktirilmagan")
        access.assertCanTouchGroupSubject(groupId, body.subjectId)

        if (body.value < 0 || body.value > body.maxValue) {
            throw ApiException.validation(
                "Baho 0 va ${body.maxValue} orasida bo'lishi kerak",
                mapOf("value" to body.value.toString()),
            )
        }

        // Baho kimdan kelgani yozilishi shart: jurnal "kim qo'ydi" degan
        // savolga javob berishi kerak. Tartib bo'yicha qidiramiz.
        val teacherId = access.teacherOrNull()?.id
            // Admin baho qo'ysa — darsning o'qituvchisi
            ?: body.lessonId?.let { lessons.findInSchool(it, schoolId)?.teacherId }
            // Dars ko'rsatilmagan bo'lsa — shu guruh va fanga biriktirilgan o'qituvchi
            ?: assignments.listByGroup(schoolId, groupId).firstOrNull { it.subjectId == body.subjectId }?.teacherId
            ?: throw ApiException.validation(
                "Bu guruh va fanga o'qituvchi biriktirilmagan — avval biriktiring",
                mapOf("subjectId" to body.subjectId.toString()),
            )

        val entity = Grade().apply {
            this.schoolId = schoolId
            this.studentId = body.studentId
            this.lessonId = body.lessonId
            this.subjectId = body.subjectId
            this.topicId = body.topicId
            this.teacherId = teacherId
            this.value = body.value
            this.maxValue = body.maxValue
            this.type = body.type
            this.comment = body.comment
            this.gradedOn = body.date ?: LocalDate.now()
        }
        grades.persist(entity)
        return mappers.grade(entity)
    }

    private fun notifyGrades(schoolId: UUID, studentIds: List<UUID>, count: Int) {
        if (!settings(schoolId).notifyParentsOnGrade) return
        notifications.notifyParentsOfStudents(
            studentIds,
            schoolId,
            MessageCategory.GRADE,
            title = "Yangi baho",
            body = if (count == 1) "Farzandingizga yangi baho qo'yildi" else "Farzandingizga $count ta yangi baho qo'yildi",
            link = "/grades",
        )
        grades.update(
            "sentToParentAt = ?1 where schoolId = ?2 and studentId in ?3 and sentToParentAt is null",
            Instant.now(), schoolId, studentIds,
        )
    }

    @Transactional
    fun deleteGrade(schoolId: UUID, id: UUID) {
        val grade = grades.requireInSchool(id, schoolId, "Baho")
        access.assertOwnsOrAdmin(grade.teacherId)
        grades.delete(grade)
    }

    /** Jurnal jadvali: qatorlar — o'quvchilar, ustunlar — sanalar. */
    fun gradebook(schoolId: UUID, groupId: UUID, subjectId: UUID, from: LocalDate, to: LocalDate): GradebookDto {
        lookups.load(schoolId)
        access.assertCanTouchGroup(groupId)
        subjects.requireInSchool(subjectId, schoolId, "Fan")

        val groupStudents = students.listByGroup(schoolId, groupId)
        lookups.primeStudents(groupStudents.map { it.id })
        val ids = groupStudents.map { it.id }
        if (ids.isEmpty()) return GradebookDto(groupId, subjectId, emptyList(), emptyList())

        val all = grades.list(
            "schoolId = ?1 and subjectId = ?2 and studentId in ?3 and gradedOn between ?4 and ?5",
            schoolId, subjectId, ids, from, to,
        )
        val dates = all.map { it.gradedOn }.distinct().sorted()
        val byStudent = all.groupBy { it.studentId }

        return GradebookDto(
            groupId = groupId,
            subjectId = subjectId,
            dates = dates,
            students = groupStudents.map { student ->
                val own = byStudent[student.id].orEmpty()
                GradebookStudentDto(
                    studentId = student.id,
                    fullName = "${student.lastName} ${student.firstName}",
                    average = if (own.isEmpty()) 0 else own.map { it.normalized }.average().roundToInt(),
                    cells = dates.map { date ->
                        GradebookCellDto(
                            studentId = student.id,
                            date = date,
                            grades = own.filter { it.gradedOn == date }.map { mappers.grade(it) },
                        )
                    },
                )
            },
        )
    }

    /* -------------------------------- Davomat ------------------------------- */

    fun listAttendance(schoolId: UUID, lessonId: UUID): List<AttendanceDto> {
        lookups.load(schoolId)
        val lesson = schedule.requireLesson(schoolId, lessonId)
        val records = attendance.listByLesson(schoolId, lessonId)
        lookups.primeStudents(records.map { it.studentId })
        return records.map { mappers.attendance(it, lesson.subjectId) }
    }

    /** O'quvchi davomati (standart — oxirgi 60 kun), yangisi birinchi. */
    fun listStudentAttendance(schoolId: UUID, studentId: UUID, from: LocalDate?, to: LocalDate?): List<AttendanceDto> {
        lookups.load(schoolId)
        val student = students.requireInSchool(studentId, schoolId, "O'quvchi")
        student.groupId?.let { access.assertCanTouchGroup(it) }
        val until = to ?: LocalDate.now()
        val records = attendance.listByStudent(schoolId, studentId, from ?: until.minusDays(60), until)
        val lessonIds = records.map { it.lessonId }.distinct()
        val subjectByLesson = if (lessonIds.isEmpty()) {
            emptyMap()
        } else {
            lessons.list("schoolId = ?1 and id in ?2", schoolId, lessonIds).associate { it.id to it.subjectId }
        }
        return records.map { mappers.attendance(it, subjectByLesson[it.lessonId]) }
    }

    @Transactional
    fun markAttendanceBulk(schoolId: UUID, records: List<AttendanceBody>): List<AttendanceDto> {
        lookups.load(schoolId)
        if (records.isEmpty()) return emptyList()

        val absent = mutableListOf<UUID>()
        val result = records.map { body ->
            val lesson = schedule.requireLesson(schoolId, body.lessonId)
            access.assertOwnsOrAdmin(lesson.teacherId)
            students.requireInSchool(body.studentId, schoolId, "O'quvchi")

            val entity = attendance.findForStudentLesson(body.studentId, body.lessonId)
                ?: Attendance().apply {
                    this.schoolId = schoolId
                    this.studentId = body.studentId
                    this.lessonId = body.lessonId
                    this.attendedOn = lesson.lessonDate
                }.also { attendance.persist(it) }

            entity.status = body.status
            entity.minutesLate = body.minutesLate
            entity.reason = body.reason
            entity.markedBy = access.teacherOrNull()?.userId
            entity.touch()

            if (body.status == AttendanceStatus.ABSENT) absent += body.studentId
            mappers.attendance(entity, lesson.subjectId)
        }

        if (absent.isNotEmpty() && settings(schoolId).notifyParentsOnAbsence) {
            notifications.notifyParentsOfStudents(
                absent.distinct(),
                schoolId,
                MessageCategory.ATTENDANCE,
                title = "Darsga kelmadi",
                body = "Farzandingiz bugungi darsda qatnashmadi",
                link = "/attendance",
            )
        }
        return result
    }

    /** Oylik davomat hisoboti: o'quvchi × kun. */
    fun attendanceJournal(schoolId: UUID, groupId: UUID, from: LocalDate, to: LocalDate): Map<String, Any> {
        lookups.load(schoolId)
        access.assertCanTouchGroup(groupId)
        val groupStudents = students.listByGroup(schoolId, groupId)
        val ids = groupStudents.map { it.id }
        if (ids.isEmpty()) return mapOf("students" to emptyList<Any>(), "dates" to emptyList<Any>())

        val records = attendance.list(
            "schoolId = ?1 and studentId in ?2 and attendedOn between ?3 and ?4",
            schoolId, ids, from, to,
        )
        val dates = records.map { it.attendedOn }.distinct().sorted()
        val byStudent = records.groupBy { it.studentId }

        return mapOf(
            "dates" to dates,
            "students" to groupStudents.map { student ->
                val own = byStudent[student.id].orEmpty()
                val present = own.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.EXCUSED }
                mapOf(
                    "studentId" to student.id,
                    "fullName" to "${student.lastName} ${student.firstName}",
                    "rate" to if (own.isEmpty()) 100 else (100.0 * present / own.size).roundToInt(),
                    "days" to own.associate { it.attendedOn.toString() to it.status.name },
                )
            },
        )
    }

    fun settings(schoolId: UUID): SchoolSettingsDto {
        val school = schools.findById(schoolId) ?: return SchoolSettingsDto()
        return Json.read(school.settings, SchoolSettingsDto())
    }
}
