package uz.farzandim.mobile.service

import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.ParentProfile
import uz.farzandim.shared.domain.Student
import uz.farzandim.shared.repo.ParentRepository
import uz.farzandim.shared.repo.StudentParentRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.security.CurrentUser
import java.util.UUID

/**
 * Ota-onaning chegarasi.
 *
 * URL'dagi `childId` ni almashtirib boshqa bolaning ma'lumotini olishga
 * urinish — eng oddiy va eng ko'p uchraydigan hujum. Shuning uchun har bir
 * so'rovda bola ID'si ota-onaning `student_parents` bog'lanishlari bilan
 * solishtiriladi. Tekshiruv kontrollerda emas, shu yerda — kontroller
 * unutilishi mumkin, servis esa har doim shu yo'ldan o'tadi.
 *
 * Begona bola so'ralsa 404 qaytadi (403 emas): shunday ID'li o'quvchi
 * borligini ham tasdiqlamaymiz.
 */
@RequestScoped
class ParentAccess {

    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var parents: ParentRepository
    @Inject lateinit var studentParents: StudentParentRepository
    @Inject lateinit var students: StudentRepository

    private var cachedProfile: ParentProfile? = null
    private var childIds: Set<UUID>? = null

    fun profile(): ParentProfile =
        cachedProfile ?: parents.findByUserId(currentUser.userId)?.also { cachedProfile = it }
            ?: throw ApiException.forbidden("Ota-ona profili topilmadi")

    /** Mustaqil (maktabsiz) foydalanuvchi — faqat AI funksiyalari. */
    val isIndependent: Boolean get() = profile().schoolId == null

    fun childIds(): Set<UUID> = childIds ?: studentParents.listByParent(profile().id)
        .map { it.studentId }.toSet().also { childIds = it }

    fun requireChild(childId: UUID): Student {
        if (childId !in childIds()) throw ApiException.notFound("Farzand", childId)
        return students.findById(childId) ?: throw ApiException.notFound("Farzand", childId)
    }

    fun schoolIdOf(child: Student): UUID = child.schoolId

    /** Maktabga bog'langan funksiyalar mustaqil foydalanuvchida yopiq. */
    fun assertSchoolBound() {
        if (isIndependent) {
            throw ApiException.forbidden(
                "Bu bo'lim maktabga ulangan hisoblar uchun. Maktabingizdan login so'rang.",
            )
        }
    }
}
