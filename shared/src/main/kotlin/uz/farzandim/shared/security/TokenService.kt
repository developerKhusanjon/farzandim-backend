package uz.farzandim.shared.security

import io.smallrye.jwt.build.Jwt
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import uz.farzandim.shared.domain.TokenAudience
import uz.farzandim.shared.domain.UserAccount
import java.time.Instant
import java.util.UUID

/**
 * Access token ichida faqat identifikatsiya bo'ladi:
 * kim, qaysi maktab, qaysi rol, qaysi sessiya.
 *
 * Bu yerga ism, telefon, o'quvchi ro'yxati yoki boshqa CRM ma'lumoti
 * QO'YILMAYDI — JWT shifrlanmagan, uni brauzerdagi har qanday skript o'qiy
 * oladi va u eskiradi. Kerakli ma'lumot `/auth/me` orqali olinadi.
 */
@ApplicationScoped
class TokenService {

    @Inject
    lateinit var settings: JwtSettings

    fun issueAccessToken(user: UserAccount, sessionId: UUID, audience: TokenAudience): AccessToken {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(settings.accessTtlSeconds())

        val builder = Jwt.issuer(settings.issuer())
            .subject(user.id.toString())
            .upn(user.id.toString())
            .audience(audience.value)
            .groups(groupsFor(user))
            .claim(Claims.ROLE, user.role.name)
            .claim(Claims.SESSION_ID, sessionId.toString())
            .claim(Claims.LOCALE, user.locale.name)
            .issuedAt(now)
            .expiresAt(expiresAt)

        user.schoolId?.let { builder.claim(Claims.TENANT_ID, it.toString()) }

        return AccessToken(builder.sign(), expiresAt, settings.accessTtlSeconds())
    }

    /**
     * `@RolesAllowed` guruhlari. Maktab superadmini qo'shimcha guruh oladi —
     * shuning uchun SCHOOL_ADMIN talab qiladigan endpointlar unda ham ishlaydi,
     * adminlarni boshqarish esa faqat SCHOOL_SUPER_ADMIN'ga ochiq.
     */
    private fun groupsFor(user: UserAccount): Set<String> =
        if (user.isSchoolSuperAdmin) setOf(user.role.name, Roles.SCHOOL_SUPER_ADMIN) else setOf(user.role.name)
}

data class AccessToken(
    val token: String,
    val expiresAt: Instant,
    val expiresInSeconds: Long,
)
