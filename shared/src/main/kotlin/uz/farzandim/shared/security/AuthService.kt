package uz.farzandim.shared.security

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.cache.RateLimiter
import uz.farzandim.shared.domain.Locale
import uz.farzandim.shared.domain.MessagePurpose
import uz.farzandim.shared.domain.OtpChannel
import uz.farzandim.shared.domain.OtpCode
import uz.farzandim.shared.domain.OtpPurpose
import uz.farzandim.shared.domain.ParentProfile
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.TokenAudience
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.notify.EmailRequest
import uz.farzandim.shared.notify.EmailRequested
import uz.farzandim.shared.notify.EmailSettings
import uz.farzandim.shared.notify.EmailTemplates
import uz.farzandim.shared.notify.PhoneNumbers
import uz.farzandim.shared.notify.SmsRequest
import uz.farzandim.shared.notify.SmsRequested
import uz.farzandim.shared.notify.SmsTemplates
import uz.farzandim.shared.platform.FeatureFlagService
import uz.farzandim.shared.platform.FlagKeys
import uz.farzandim.shared.platform.PlanAccess
import uz.farzandim.shared.platform.PlanFeatures
import uz.farzandim.shared.repo.OtpRepository
import uz.farzandim.shared.repo.ParentRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.util.Credentials
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Autentifikatsiya — uchala servis uchun umumiy.
 *
 * Har bir servis o'z `TokenAudience` si bilan chaqiradi va o'z rol
 * ro'yxatini beradi: ota-ona tokeni bilan maktab CRM'iga kirish mumkin emas,
 * chunki token `aud` da `farzandim-mobile` turadi va school-backend uni
 * qabul qilmaydi.
 */
@ApplicationScoped
class AuthService {

    private val log = Logger.getLogger(AuthService::class.java)

    @Inject lateinit var users: UserRepository
    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var parents: ParentRepository
    @Inject lateinit var otps: OtpRepository
    @Inject lateinit var tokens: TokenService
    @Inject lateinit var refreshTokens: RefreshTokenService
    @Inject lateinit var rateLimiter: RateLimiter
    @Inject lateinit var authSettings: AuthSettings
    @Inject lateinit var smsEvents: Event<SmsRequested>
    @Inject lateinit var emailEvents: Event<EmailRequested>
    @Inject lateinit var emailSettings: EmailSettings
    @Inject lateinit var planAccess: PlanAccess
    @Inject lateinit var flags: FeatureFlagService

    /* --------------------------- Login / parol ----------------------------- */

    @Transactional
    fun login(
        request: LoginRequest,
        audience: TokenAudience,
        allowedRoles: Set<Role>,
        ipAddress: String? = null,
    ): AuthResponse {
        // Brute force himoyasi: login bo'yicha ham, IP bo'yicha ham.
        rateLimiter.check("login", request.login.lowercase(), authSettings.loginAttemptsPerMinute(), Duration.ofMinutes(1))
        ipAddress?.let { rateLimiter.check("login-ip", it, authSettings.loginAttemptsPerMinute() * 3, Duration.ofMinutes(1)) }

        val user = users.findByLogin(request.login)

        // Vaqt bo'yicha sizib chiqishni kamaytirish uchun parolni baribir tekshiramiz.
        val passwordOk = Passwords.matches(request.password, user?.passwordHash)
        if (user == null || !passwordOk) {
            throw ApiException.unauthorized("Login yoki parol noto'g'ri")
        }
        assertLoginAllowed(user, allowedRoles, audience)

        rateLimiter.reset("login", request.login.lowercase())
        return buildSession(user, audience, request.deviceInfo, ipAddress)
    }

    private fun assertLoginAllowed(user: UserAccount, allowedRoles: Set<Role>, audience: TokenAudience) {
        if (user.role !in allowedRoles) {
            throw ApiException.forbidden("Bu ilovaga kirish huquqi yo'q")
        }
        when (user.status) {
            UserStatus.BLOCKED -> throw ApiException.forbidden("Hisob bloklangan")
            UserStatus.INVITED, UserStatus.ACTIVE -> Unit
        }
        assertSchoolAllowed(user, audience)
    }

    /**
     * Maktab holati, obunasi va (mobil ilova uchun) tarifdagi `mobileApp`.
     * Login'da ham, refresh'da ham tekshiriladi.
     */
    private fun assertSchoolAllowed(user: UserAccount, audience: TokenAudience) {
        val schoolId = user.schoolId ?: return
        val school = schools.findById(schoolId) ?: throw ApiException.forbidden("Maktab topilmadi")
        planAccess.assertSchoolOperational(school)
        if (audience == TokenAudience.MOBILE && !planAccess.hasFeature(school, PlanFeatures.MOBILE_APP)) {
            throw ApiException.featureNotAvailable("Maktab tarifida ota-ona ilovasi yoqilmagan")
        }
    }

    @Transactional
    fun buildSession(
        user: UserAccount,
        audience: TokenAudience,
        deviceInfo: String?,
        ipAddress: String?,
    ): AuthResponse {
        val familyId = UUID.randomUUID()
        val refresh = refreshTokens.issue(user, audience, familyId, deviceInfo, ipAddress)
        val access = tokens.issueAccessToken(user, familyId, audience)

        user.lastLoginAt = Instant.now()
        if (user.status == UserStatus.INVITED) user.status = UserStatus.ACTIVE
        users.persist(user)

        return AuthResponse(
            accessToken = access.token,
            refreshToken = refresh.raw,
            expiresIn = access.expiresInSeconds,
            expiresAt = access.expiresAt,
            user = AuthUserDto.from(user),
            schools = schoolsFor(user),
        )
    }

    @Transactional
    fun refresh(rawToken: String, audience: TokenAudience, ipAddress: String? = null): AuthResponse {
        val (old, issued) = refreshTokens.rotate(rawToken, audience, ipAddress)
        val user = users.findById(old.userId) ?: throw ApiException.unauthorized("Foydalanuvchi topilmadi")
        if (user.status == UserStatus.BLOCKED) {
            refreshTokens.revokeAllForUser(user.id, "USER_BLOCKED")
            throw ApiException.forbidden("Hisob bloklangan")
        }
        // Obuna tugagan yoki maktab to'xtatilgan bo'lsa — sessiya shu yerda to'xtaydi.
        assertSchoolAllowed(user, audience)
        val access = tokens.issueAccessToken(user, issued.entity.familyId, audience)
        return AuthResponse(
            accessToken = access.token,
            refreshToken = issued.raw,
            expiresIn = access.expiresInSeconds,
            expiresAt = access.expiresAt,
            user = AuthUserDto.from(user),
            schools = schoolsFor(user),
        )
    }

    fun logout(rawToken: String?) {
        if (!rawToken.isNullOrBlank()) refreshTokens.revoke(rawToken)
    }

    @Transactional
    fun changePassword(userId: UUID, body: ChangePasswordBody) {
        val user = users.findById(userId) ?: throw ApiException.unauthorized()
        if (!Passwords.matches(body.currentPassword, user.passwordHash)) {
            throw ApiException.validation("Joriy parol noto'g'ri", mapOf("currentPassword" to "noto'g'ri"))
        }
        Passwords.validate(body.newPassword)
        user.passwordHash = Passwords.hash(body.newPassword)
        user.mustChangePassword = false
        user.touch()
        // Parol o'zgardi — boshqa barcha qurilmalardagi sessiyalar yopiladi.
        refreshTokens.revokeAllForUser(userId, "PASSWORD_CHANGED")
    }

    /* -------------------------------- OTP ---------------------------------- */

    /**
     * Mustaqil ota-ona uchun kirish kodi.
     *
     * Javob har doim bir xil: raqam ro'yxatdan o'tganmi yoki yo'qmi —
     * tashqaridan bilib bo'lmaydi (enumeration himoyasi).
     */
    @Transactional
    fun requestOtp(body: OtpRequestBody): OtpRequestResponse {
        val (channel, recipient) = resolveChannel(body.phone, body.email)
        rateLimiter.check("otp", recipient, authSettings.otpPerHour(), Duration.ofHours(1))

        val code = Credentials.numericCode(6)
        otps.persist(
            OtpCode().apply {
                this.channel = channel
                this.recipient = recipient
                this.codeHash = Credentials.sha256(code)
                this.purpose = OtpPurpose.LOGIN
                this.expiresAt = Instant.now().plusSeconds(authSettings.otpTtlSeconds().toLong())
            },
        )

        // Kod faqat SMS matnida yashaydi: bazada hash, logda umuman yo'q.
        // Hodisa tranzaksiya commit bo'lgandan keyin ishlaydi — kod yozuvi
        // saqlanmasa, SMS ham ketmaydi.
        val existing = users.findByEmailOrPhone(recipient)
        val locale = existing?.locale ?: Locale.uz
        val minutes = authSettings.otpTtlSeconds() / 60

        if (channel == OtpChannel.PHONE) {
            smsEvents.fire(
                SmsRequested(
                    SmsRequest(
                        recipient = recipient,
                        text = SmsTemplates.otp(code, locale, minutes),
                        purpose = MessagePurpose.OTP,
                        schoolId = existing?.schoolId,
                        userId = existing?.id,
                    ),
                ),
            )
        } else {
            emailEvents.fire(
                EmailRequested(
                    EmailRequest(
                        recipient = recipient,
                        subject = EmailTemplates.otpSubject(locale),
                        html = EmailTemplates.otpHtml(code, locale, minutes, emailSettings.signature()),
                        text = EmailTemplates.otpText(code, locale, minutes),
                        purpose = MessagePurpose.OTP,
                        schoolId = existing?.schoolId,
                        userId = existing?.id,
                    ),
                ),
            )
        }

        return OtpRequestResponse(
            sent = true,
            channel = channel.name,
            expiresInSeconds = authSettings.otpTtlSeconds(),
            debugCode = if (authSettings.otpEchoCode()) code else null,
        )
    }

    /**
     * Kodni tekshiradi. Foydalanuvchi bo'lmasa — mustaqil ota-ona sifatida
     * yaratiladi (`school_id = null`, faqat AI funksiyalari).
     */
    @Transactional
    fun verifyOtp(body: OtpVerifyBody, audience: TokenAudience, ipAddress: String? = null): AuthResponse {
        val (channel, recipient) = resolveChannel(body.phone, body.email)
        rateLimiter.check("otp-verify", recipient, authSettings.otpMaxAttempts() * 3, Duration.ofMinutes(15))

        val otp = otps.findLatest(recipient, OtpPurpose.LOGIN)
            ?: throw ApiException.validation("Kod topilmadi yoki muddati tugagan", mapOf("code" to "noto'g'ri"))

        if (otp.expiresAt.isBefore(Instant.now())) {
            throw ApiException.validation("Kod muddati tugagan", mapOf("code" to "eskirgan"))
        }
        if (otp.attempts >= authSettings.otpMaxAttempts()) {
            throw ApiException.validation("Urinishlar soni tugadi, yangi kod so'rang", mapOf("code" to "bloklangan"))
        }
        if (otp.codeHash != Credentials.sha256(body.code.trim())) {
            otp.attempts += 1
            throw ApiException.validation("Kod noto'g'ri", mapOf("code" to "noto'g'ri"))
        }

        otp.consumedAt = Instant.now()

        val user = users.findByEmailOrPhone(recipient)
            ?: if (flags.isEnabled(FlagKeys.PARENT_INDEPENDENT, null)) {
                createIndependentParent(channel, recipient, body)
            } else {
                throw ApiException.featureNotAvailable("Mustaqil ro'yxatdan o'tish vaqtincha yopiq")
            }
        if (user.role != Role.PARENT) {
            throw ApiException.forbidden("Bu hisob uchun kod bilan kirish yoqilmagan")
        }
        if (user.status == UserStatus.BLOCKED) throw ApiException.forbidden("Hisob bloklangan")

        return buildSession(user, audience, body.deviceInfo, ipAddress)
    }

    private fun createIndependentParent(channel: OtpChannel, recipient: String, body: OtpVerifyBody): UserAccount {
        val user = UserAccount().apply {
            this.schoolId = null
            this.role = Role.PARENT
            this.firstName = body.firstName?.trim().orEmpty().ifBlank { "Ota-ona" }
            this.lastName = body.lastName?.trim().orEmpty()
            this.locale = Locale.uz
            this.status = UserStatus.ACTIVE
            if (channel == OtpChannel.PHONE) this.phone = recipient else this.email = recipient
        }
        users.persist(user)
        parents.persist(
            ParentProfile().apply {
                this.userId = user.id
                this.schoolId = null
            },
        )
        log.infof("Mustaqil ota-ona yaratildi: %s", mask(recipient))
        return user
    }

    /* ------------------------------ Yordamchi ------------------------------ */

    fun schoolsFor(user: UserAccount): List<SchoolBriefDto> {
        val schoolId = user.schoolId ?: return emptyList()
        val school = schools.findById(schoolId) ?: return emptyList()
        return listOf(
            SchoolBriefDto(school.id, school.name, school.shortName, school.logoUrl, school.academicYear),
        )
    }

    /**
     * Raqam har doim bitta ko'rinishda saqlanadi (`+998901234567`) — aks holda
     * `+998 90 123 45 67` bilan so'ralgan kod `998901234567` bilan
     * tekshirilganda topilmaydi.
     */
    private fun resolveChannel(phone: String?, email: String?): Pair<OtpChannel, String> {
        val rawPhone = phone?.takeIf { it.isNotBlank() }
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        if (rawPhone != null) {
            // Noto'g'ri formatdagi raqam — aniq xabar beramiz, email'ga o'tib ketmaymiz.
            return OtpChannel.PHONE to PhoneNumbers.e164(rawPhone)
        }
        if (normalizedEmail != null) {
            if (!normalizedEmail.contains('@') || !normalizedEmail.contains('.')) {
                throw ApiException.validation("Email noto'g'ri formatda", mapOf("email" to "masalan ism@pochta.uz"))
            }
            return OtpChannel.EMAIL to normalizedEmail
        }
        throw ApiException.validation(
            "Telefon yoki email ko'rsatilishi kerak",
            mapOf("phone" to "to'ldiring"),
        )
    }

    private fun mask(value: String): String =
        if (value.length <= 4) "***" else value.take(3) + "***" + value.takeLast(2)
}
