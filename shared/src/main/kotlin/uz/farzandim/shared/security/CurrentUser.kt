package uz.farzandim.shared.security

import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.jwt.JsonWebToken
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.Role
import java.util.UUID

/**
 * So'rov doirasidagi foydalanuvchi.
 *
 * Diqqat: bu yerdagi ma'lumot — tokenga imzolangan da'vo (claim), ya'ni
 * "kim ekanligi". "Nimaga ruxsati bor"ligi — har doim bazadagi egalik
 * tekshiruvi (repository qatlami), token ichidagi rol emas.
 */
@RequestScoped
class CurrentUser {

    @Inject
    lateinit var jwt: JsonWebToken

    /** `X-School-Id` sarlavhasi — TenantRequestFilter yozadi. */
    var requestedSchoolId: UUID? = null

    val authenticated: Boolean
        get() = jwt.rawToken != null

    val userId: UUID
        get() = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ApiException.unauthorized()

    val userIdOrNull: UUID?
        get() = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    val role: Role
        get() = jwt.getClaim<String>(Claims.ROLE)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
            ?: throw ApiException.unauthorized()

    /** Tokenda imzolangan maktab. SUPER_ADMIN uchun `null`. */
    val tokenSchoolId: UUID?
        get() = jwt.getClaim<String>(Claims.TENANT_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Sessiya oilasi — logout va reuse-detection uchun. */
    val sessionId: UUID?
        get() = jwt.getClaim<String>(Claims.SESSION_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    val isSuperAdmin: Boolean get() = jwt.getClaim<String>(Claims.ROLE) == Role.SUPER_ADMIN.name

    /**
     * Amal bajariladigan maktab.
     *
     * Oddiy foydalanuvchida — tokendagi maktab; `X-School-Id` boshqa qiymat
     * ko'rsatsa, TenantRequestFilter allaqachon 403 bergan bo'ladi.
     * SUPER_ADMIN uchun — sarlavhadagi maktab (u maktablar orasida harakatlanadi).
     */
    fun requireSchoolId(): UUID =
        (if (isSuperAdmin) requestedSchoolId else tokenSchoolId)
            ?: throw ApiException.schoolContextRequired()

    fun schoolIdOrNull(): UUID? = if (isSuperAdmin) requestedSchoolId else tokenSchoolId

    fun requireRole(vararg allowed: Role) {
        if (role !in allowed) throw ApiException.forbidden()
    }
}

object Claims {
    const val TENANT_ID = "tenant_id"
    const val ROLE = "role"
    const val SESSION_ID = "sid"
    const val LOCALE = "locale"
}
