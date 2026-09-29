package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.LessonDto
import uz.farzandim.school.dto.LessonUpdateBody
import uz.farzandim.school.dto.ScheduleSlotBody
import uz.farzandim.school.dto.ScheduleSlotDto
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.Lesson
import uz.farzandim.shared.domain.LessonStatus
import uz.farzandim.shared.domain.ScheduleSlot
import uz.farzandim.shared.repo.GroupRepository
import uz.farzandim.shared.repo.LessonRepository
import uz.farzandim.shared.repo.ScheduleSlotRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.repo.TeacherRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Jadval va darslar.
 *
 * Jadval — takrorlanuvchi shablon (`schedule_slots`), dars — aniq sanadagi
 * hodisa (`lessons`). Dars yozuvi jadvaldan avtomatik "materiallashadi":
 * o'qituvchi darsni boshlaganda yoki kun ro'yxati so'ralganda.
 * Shu sababli baho va davomat har doim aniq darsga bog'lanadi.
 */
@ApplicationScoped
class ScheduleService {

    @Inject lateinit var slots: ScheduleSlotRepository
    @Inject lateinit var lessons: LessonRepository
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var teachers: TeacherRepository
    @Inject lateinit var access: AccessControl
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var mappers: Mappers

    /* --------------------------------- Jadval ------------------------------- */

    fun listSlots(schoolId: UUID, groupId: UUID?, teacherId: UUID?): List<ScheduleSlotDto> {
        lookups.load(schoolId)
        val visible = access.visibleGroupIds()
        val result = when {
            groupId != null -> {
                access.assertCanTouchGroup(groupId)
                slots.listByGroup(schoolId, groupId)
            }
            teacherId != null -> slots.listByTeacher(schoolId, teacherId)
            else -> slots.listInSchool(schoolId)
        }
        return result
            .filter { visible == null || it.groupId in visible }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startTime }))
            .map { mappers.scheduleSlot(it) }
    }

    @Transactional
    fun createSlot(schoolId: UUID, body: ScheduleSlotBody): ScheduleSlotDto {
        access.assertAdmin()
        groups.requireInSchool(body.groupId, schoolId, "Guruh")
        subjects.requireInSchool(body.subjectId, schoolId, "Fan")
        teachers.requireInSchool(body.teacherId, schoolId, "O'qituvchi")
        if (!body.endTime.isAfter(body.startTime)) {
            throw ApiException.validation("Dars tugash vaqti boshlanish vaqtidan keyin bo'lishi kerak")
        }
        assertNoClash(schoolId, body, null)

        val entity = ScheduleSlot().apply {
            this.schoolId = schoolId
            this.groupId = body.groupId
            this.subjectId = body.subjectId
            this.teacherId = body.teacherId
            this.dayOfWeek = body.dayOfWeek
            this.startTime = body.startTime
            this.endTime = body.endTime
            this.room = body.room
        }
        slots.persist(entity)
        lookups.load(schoolId)
        return mappers.scheduleSlot(entity)
    }

    @Transactional
    fun updateSlot(schoolId: UUID, id: UUID, body: ScheduleSlotBody): ScheduleSlotDto {
        access.assertAdmin()
        val entity = slots.requireInSchool(id, schoolId, "Jadval katakchasi")
        assertNoClash(schoolId, body, id)
        entity.groupId = body.groupId
        entity.subjectId = body.subjectId
        entity.teacherId = body.teacherId
        entity.dayOfWeek = body.dayOfWeek
        entity.startTime = body.startTime
        entity.endTime = body.endTime
        entity.room = body.room
        entity.touch()
        lookups.load(schoolId)
        return mappers.scheduleSlot(entity)
    }

    @Transactional
    fun deleteSlot(schoolId: UUID, id: UUID) {
        access.assertAdmin()
        if (!slots.deleteInSchool(id, schoolId)) throw ApiException.notFound("Jadval katakchasi", id)
    }

    /** Bir o'qituvchi yoki bir guruh bitta vaqtda ikki joyda bo'lolmaydi. */
    private fun assertNoClash(schoolId: UUID, body: ScheduleSlotBody, excludeId: UUID?) {
        val sameDay = slots.listForDay(schoolId, body.dayOfWeek).filter { it.id != excludeId }
        val clash = sameDay.firstOrNull { existing ->
            val overlaps = body.startTime < existing.endTime && existing.startTime < body.endTime
            overlaps && (existing.groupId == body.groupId || existing.teacherId == body.teacherId)
        }
        if (clash != null) {
            val who = if (clash.teacherId == body.teacherId) "O'qituvchi" else "Guruh"
            throw ApiException.conflict(
                "$who bu vaqtda band: ${clash.startTime}–${clash.endTime}",
            )
        }
    }

    /* --------------------------------- Darslar ------------------------------ */

    fun listLessons(schoolId: UUID, date: LocalDate?, groupId: UUID?, teacherId: UUID?): List<LessonDto> {
        lookups.load(schoolId)
        val day = date ?: LocalDate.now()
        materializeDay(schoolId, day)

        val visible = access.visibleGroupIds()
        val found = when {
            groupId != null -> {
                access.assertCanTouchGroup(groupId)
                lessons.listByGroupAndRange(schoolId, groupId, day, day)
            }
            teacherId != null -> lessons.listByTeacherAndDate(schoolId, teacherId, day)
            else -> lessons.listByDate(schoolId, day)
        }
        return found
            .filter { visible == null || it.groupId in visible }
            .map { mappers.lesson(it) }
    }

    fun getLesson(schoolId: UUID, id: UUID): LessonDto {
        lookups.load(schoolId)
        val lesson = lessons.requireInSchool(id, schoolId, "Dars")
        access.assertCanTouchGroup(lesson.groupId)
        return mappers.lesson(lesson)
    }

    fun requireLesson(schoolId: UUID, id: UUID): Lesson {
        val lesson = lessons.requireInSchool(id, schoolId, "Dars")
        access.assertCanTouchGroup(lesson.groupId)
        return lesson
    }

    /**
     * Jadvaldan kunlik dars yozuvlarini yaratish.
     * Idempotent: `lessons_slot_date_key` unikal indeksi takrorlanishga yo'l qo'ymaydi.
     */
    @Transactional
    fun materializeDay(schoolId: UUID, date: LocalDate) {
        val dayOfWeek = date.dayOfWeek.value
        val daySlots = slots.listForDay(schoolId, dayOfWeek)
            .filter { it.activeFrom <= date && (it.activeTo == null || it.activeTo!! >= date) }
        if (daySlots.isEmpty()) return

        for (slot in daySlots) {
            if (lessons.findBySlotAndDate(slot.id, date) != null) continue
            lessons.persist(
                Lesson().apply {
                    this.schoolId = schoolId
                    this.scheduleSlotId = slot.id
                    this.groupId = slot.groupId
                    this.subjectId = slot.subjectId
                    this.teacherId = slot.teacherId
                    this.lessonDate = date
                    this.startTime = slot.startTime
                    this.endTime = slot.endTime
                    this.room = slot.room
                    this.title = ""
                },
            )
        }
    }

    @Transactional
    fun updateLesson(schoolId: UUID, id: UUID, body: LessonUpdateBody): LessonDto {
        lookups.load(schoolId)
        val lesson = requireLesson(schoolId, id)
        access.assertOwnsOrAdmin(lesson.teacherId)
        body.title?.let { lesson.title = it.trim() }
        body.topicId?.let { lesson.topicId = it }
        body.teacherNotes?.let { lesson.teacherNotes = it }
        body.status?.let { lesson.status = it }
        lesson.touch()
        return mappers.lesson(lesson)
    }

    @Transactional
    fun startLesson(schoolId: UUID, id: UUID): LessonDto {
        lookups.load(schoolId)
        val lesson = requireLesson(schoolId, id)
        access.assertOwnsOrAdmin(lesson.teacherId)
        lesson.status = LessonStatus.IN_PROGRESS
        lesson.startedAt = Instant.now()
        lesson.touch()
        return mappers.lesson(lesson)
    }

    @Transactional
    fun finishLesson(schoolId: UUID, id: UUID): LessonDto {
        lookups.load(schoolId)
        val lesson = requireLesson(schoolId, id)
        access.assertOwnsOrAdmin(lesson.teacherId)
        lesson.status = LessonStatus.DONE
        lesson.finishedAt = Instant.now()
        lesson.touch()
        return mappers.lesson(lesson)
    }
}
