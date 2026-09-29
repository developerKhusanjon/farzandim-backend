package uz.farzandim.shared.security

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.RefreshToken
import uz.farzandim.shared.domain.TokenAudience
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.repo.RefreshTokenRepository
import uz.farzandim.shared.util.Credentials
import java.time.Instant
import java.util.UUID

/**
 * Refresh token rotatsiyasi va bekor qilish.
 *
 * Qoida: har bir refresh — eski tokenni o'ldiradi va yangisini beradi.
 * Allaqachon ishlatilgan token qayta kelsa, bu ikki holatdan biri:
 *   1) token o'g'irlangan va o'g'ri undan foydalanmoqda;
 *   2) mijozda tarmoq uzilib, javob yetib bormagan.
 * Ikkalasini farqlab bo'lmaydi, shuning uchun xavfsiz yo'l tanlanadi:
 * butun sessiya oilasi bekor qilinadi va foydalanuvchi qayta kiradi.
 */
/**
 * Sessiya oilasini alohida tranzaksiyada bekor qiladi.
 *
 * Nega alohida bean va REQUIRES_NEW: token qayta ishlatilganini aniqlaganda
 * biz ham bekor qilishimiz, ham 401 tashlashimiz kerak. Istisno esa joriy
 * tranzaksiyani qaytarib yuboradi — bekor qilish yozuvi yo'qoladi va
 * o'g'irlangan token yashashda davom etardi. Shuning uchun bekor qilish
 * mustaqil tranzaksiyada commit bo'ladi. Self-invocation interceptor'ni
 * chetlab o'tgani uchun bu alohida bean bo'lishi shart.
 */
@ApplicationScoped
class TokenRevoker {

    @Inject lateinit var repository: RefreshTokenRepository

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun revokeFamilyNow(familyId: UUID, reason: String) {
        repository.revokeFamily(familyId, reason)
    }
}

@ApplicationScoped
class RefreshTokenService {

    private val log = Logger.getLogger(RefreshTokenService::class.java)

    @Inject lateinit var repository: RefreshTokenRepository
    @Inject lateinit var settings: JwtSettings
    @Inject lateinit var revoker: TokenRevoker

    data class Issued(val raw: String, val entity: RefreshToken)

    @Transactional
    fun issue(
        user: UserAccount,
        audience: TokenAudience,
        familyId: UUID = UUID.randomUUID(),
        deviceInfo: String? = null,
        ipAddress: String? = null,
    ): Issued {
        val raw = Credentials.randomToken()
        val entity = RefreshToken().apply {
            this.userId = user.id
            this.familyId = familyId
            this.tokenHash = Credentials.sha256(raw)
            this.audience = audience.value
            this.deviceInfo = deviceInfo?.take(200)
            this.ipAddress = ipAddress
            this.expiresAt = Instant.now().plusSeconds(settings.refreshTtlSeconds())
        }
        repository.persist(entity)
        return Issued(raw, entity)
    }

    /**
     * Tokenni tekshiradi va almashtiradi. Qaytadi: yangi token + sessiya oilasi.
     * Noto'g'ri, eskirgan yoki qayta ishlatilgan token — 401.
     */
    @Transactional
    fun rotate(rawToken: String, audience: TokenAudience, ipAddress: String? = null): Pair<RefreshToken, Issued> {
        val hash = Credentials.sha256(rawToken)
        val existing = repository.findByHash(hash)
            ?: throw ApiException.unauthorized("Refresh token yaroqsiz")

        if (existing.audience != audience.value) {
            throw ApiException.unauthorized("Token boshqa ilova uchun berilgan")
        }

        if (existing.revokedAt != null) {
            // Qayta ishlatish — o'g'irlangan deb hisoblaymiz.
            // Alohida tranzaksiyada: quyidagi istisno joriy tranzaksiyani
            // qaytarib yuboradi, bekor qilish esa saqlanib qolishi shart.
            revoker.revokeFamilyNow(existing.familyId, "REUSE_DETECTED")
            log.warnf(
                "Refresh token qayta ishlatildi, sessiya oilasi bekor qilindi: user=%s family=%s",
                existing.userId, existing.familyId,
            )
            throw ApiException.unauthorized("Sessiya bekor qilindi, qaytadan kiring")
        }

        if (existing.expiresAt.isBefore(Instant.now())) {
            throw ApiException.unauthorized("Refresh token muddati tugagan")
        }

        val raw = Credentials.randomToken()
        val replacement = RefreshToken().apply {
            this.userId = existing.userId
            this.familyId = existing.familyId
            this.tokenHash = Credentials.sha256(raw)
            this.audience = audience.value
            this.deviceInfo = existing.deviceInfo
            this.ipAddress = ipAddress ?: existing.ipAddress
            this.expiresAt = Instant.now().plusSeconds(settings.refreshTtlSeconds())
        }
        repository.persist(replacement)

        existing.revokedAt = Instant.now()
        existing.revokeReason = "ROTATED"
        existing.replacedBy = replacement.id

        return existing to Issued(raw, replacement)
    }

    @Transactional
    fun revoke(rawToken: String, reason: String = "LOGOUT") {
        val existing = repository.findByHash(Credentials.sha256(rawToken)) ?: return
        repository.revokeFamily(existing.familyId, reason)
    }

    @Transactional
    fun revokeSession(familyId: UUID, reason: String = "LOGOUT") {
        repository.revokeFamily(familyId, reason)
    }

    /** Qaytadi: bekor qilingan tokenlar soni. */
    @Transactional
    fun revokeAllForUser(userId: UUID, reason: String): Int =
        repository.revokeAllForUser(userId, reason)
}
