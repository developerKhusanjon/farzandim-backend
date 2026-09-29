package uz.farzandim.shared.repo

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import uz.farzandim.shared.api.ApiException
import java.util.UUID

/**
 * Tenant chegarasi — kontrollerda emas, shu yerda.
 *
 * Kontrollerdagi tekshiruv yetarli emas: bitta unutilgan endpoint butun
 * maktablararo sizib chiqishga olib keladi. Shuning uchun har bir o'qish
 * `school_id` sharti bilan ketadi va "URL'dagi UUID'ni almashtirib ko'raman"
 * hujumi 404 bilan tugaydi — 403 emas, chunki begona maktab resursi
 * mavjudligini ham oshkor qilmaymiz.
 */
interface TenantRepository<E : Any> : PanacheRepositoryBase<E, UUID> {

    fun findInSchool(id: UUID, schoolId: UUID): E? =
        find("id = ?1 and schoolId = ?2", id, schoolId).firstResult()

    fun requireInSchool(id: UUID, schoolId: UUID, label: String): E =
        findInSchool(id, schoolId) ?: throw ApiException.notFound(label, id)

    fun listInSchool(schoolId: UUID): List<E> = list("schoolId", schoolId)

    fun countInSchool(schoolId: UUID): Long = count("schoolId", schoolId)

    fun deleteInSchool(id: UUID, schoolId: UUID): Boolean =
        delete("id = ?1 and schoolId = ?2", id, schoolId) > 0
}
