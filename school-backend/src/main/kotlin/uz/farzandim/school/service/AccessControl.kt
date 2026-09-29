package uz.farzandim.school.service

import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.Teacher
import uz.farzandim.shared.repo.TeacherAssignmentRepository
import uz.farzandim.shared.repo.TeacherRepository
import uz.farzandim.shared.security.CurrentUser
import java.util.UUID

/**
 * Rol ichidagi chegaralar.
 *
 * Tenant izolyatsiyasi (`school_id`) repository qatlamida hal qilingan.
 * Bu yerda ikkinchi savol: maktab ichida ham o'qituvchi HAMMA narsaga
 * tegishi kerak emas. U faqat o'zi dars beradigan guruh va fanlarga
 * yozishi mumkin; maktab admini esa butun maktabga.
 *
 * Bu tekshiruvlar servis qatlamida chaqiriladi — kontrollerdagi
 * `@RolesAllowed` faqat "qaysi rol umuman kirishi mumkin"ligini aytadi.
 */
@RequestScoped
class AccessControl {

    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var teachers: TeacherRepository
    @Inject lateinit var assignments: TeacherAssignmentRepository

    private var cachedTeacher: Teacher? = null
    private var teacherResolved = false

    val schoolId: UUID get() = currentUser.requireSchoolId()

    val isAdmin: Boolean
        get() = currentUser.role == Role.SCHOOL_ADMIN || currentUser.role == Role.SUPER_ADMIN

    val isTeacher: Boolean get() = currentUser.role == Role.TEACHER

    /** Joriy foydalanuvchining o'qituvchi profili (agar o'qituvchi bo'lsa). */
    fun teacherOrNull(): Teacher? {
        if (!teacherResolved) {
            cachedTeacher = if (isTeacher) teachers.findByUserId(currentUser.userId) else null
            teacherResolved = true
        }
        return cachedTeacher
    }

    fun requireTeacher(): Teacher =
        teacherOrNull() ?: throw ApiException.forbidden("O'qituvchi profili topilmadi")

    /** Admin — hamma guruhga; o'qituvchi — faqat o'zi dars beradiganiga. */
    fun assertCanTouchGroup(groupId: UUID) {
        if (isAdmin) return
        val teacher = requireTeacher()
        val ownsAsClassTeacher = assignments.teachesGroup(teacher.id, groupId)
        if (!ownsAsClassTeacher) {
            throw ApiException.forbidden("Bu guruh sizga biriktirilmagan")
        }
    }

    fun assertCanTouchGroupSubject(groupId: UUID, subjectId: UUID) {
        if (isAdmin) return
        val teacher = requireTeacher()
        if (!assignments.teaches(teacher.id, groupId, subjectId)) {
            throw ApiException.forbidden("Bu guruh va fan sizga biriktirilmagan")
        }
    }

    /** Dars/vazifa egasi yoki admin. */
    fun assertOwnsOrAdmin(ownerTeacherId: UUID) {
        if (isAdmin) return
        val teacher = requireTeacher()
        if (teacher.id != ownerTeacherId) {
            throw ApiException.forbidden("Bu yozuv boshqa o'qituvchiga tegishli")
        }
    }

    fun assertAdmin() {
        if (!isAdmin) throw ApiException.forbidden("Faqat maktab administratori bajara oladi")
    }

    /** O'qituvchi ko'rishi mumkin bo'lgan guruhlar; admin uchun `null` (cheklov yo'q). */
    fun visibleGroupIds(): Set<UUID>? {
        if (isAdmin) return null
        val teacher = teacherOrNull() ?: return emptySet()
        return assignments.listByTeacher(schoolId, teacher.id).map { it.groupId }.toSet()
    }
}
