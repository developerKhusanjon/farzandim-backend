package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.CreateSchoolAdminBody
import uz.farzandim.school.dto.CredentialsDto
import uz.farzandim.school.dto.SchoolAdminDto
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.AuditEntry
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.SchoolAdminLevel
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.notify.CredentialsNotifier
import uz.farzandim.shared.notify.EmailAddresses
import uz.farzandim.shared.notify.PhoneNumbers
import uz.farzandim.shared.repo.AuditRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Passwords
import uz.farzandim.shared.security.RefreshTokenService
import uz.farzandim.shared.util.Credentials
import uz.farzandim.shared.util.Json
import java.util.UUID

/**
 * Maktab o'z adminlarini boshqaradi (maktab superadmini).
 *
 * Platforma maktabni ulaganda bir yoki bir nechta admin beradi; keyin
 * maktab superadmini xodimlar almashganda platformaga murojaat qilmasdan
 * admin qo'sha, bloklay va darajasini o'zgartira oladi.
 *
 * Himoya: maktabda har doim kamida bitta faol superadmin qoladi va hech kim
 * o'zini bloklay yoki darajasini tushira olmaydi — aks holda maktab
 * boshqaruvsiz qolib, faqat platforma orqali tiklanadi.
 */
@ApplicationScoped
class SchoolAdminsService {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var refreshTokens: RefreshTokenService
    @Inject lateinit var credentialsNotifier: CredentialsNotifier
    @Inject lateinit var audit: AuditRepository
    @Inject lateinit var currentUser: CurrentUser

    fun list(schoolId: UUID): List<SchoolAdminDto> =
        users.listBySchoolAndRole(schoolId, Role.SCHOOL_ADMIN)
            .sortedWith(compareBy({ !it.isSchoolSuperAdmin }, { it.lastName }))
            .map { toDto(it) }

    @Transactional
    fun create(schoolId: UUID, body: CreateSchoolAdminBody): CredentialsDto {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        val phone = body.phone?.takeIf { it.isNotBlank() }?.let(PhoneNumbers::e164)
        val email = body.email?.takeIf { it.isNotBlank() }?.let(EmailAddresses::normalize)
        if (phone != null && users.count("phone", phone) > 0) {
            throw ApiException.conflict("Bu telefon raqami allaqachon ro'yxatdan o'tgan")
        }
        if (email != null && users.count("lower(email) = ?1", email) > 0) {
            throw ApiException.conflict("Bu email allaqachon ro'yxatdan o'tgan")
        }

        val username = Credentials.suggestUsername(body.firstName, body.lastName) { users.usernameTaken(it) }
        val password = Credentials.randomPassword(12)
        val user = UserAccount().apply {
            this.schoolId = schoolId
            this.role = Role.SCHOOL_ADMIN
            this.adminLevel = body.level
            this.firstName = body.firstName.trim()
            this.lastName = body.lastName.trim()
            this.username = username
            this.phone = phone
            this.email = email
            this.locale = school.locale
            this.passwordHash = Passwords.hash(password)
            this.status = UserStatus.INVITED
            this.mustChangePassword = true
        }
        users.persist(user)
        credentialsNotifier.send(phone, username, password, schoolId, user.id, user.locale, email)
        record("SCHOOL_ADMIN_CREATED", user, mapOf("level" to body.level.name))
        return CredentialsDto(username, password)
    }

    @Transactional
    fun setLevel(schoolId: UUID, userId: UUID, level: SchoolAdminLevel): SchoolAdminDto {
        val user = requireAdmin(schoolId, userId)
        if (user.id == currentUser.userId) throw ApiException.validation("O'z darajangizni o'zgartira olmaysiz")
        val previous = user.adminLevel ?: SchoolAdminLevel.SUPER
        if (previous == level) return toDto(user)
        if (previous == SchoolAdminLevel.SUPER && level == SchoolAdminLevel.REGULAR) assertNotLastSuper(schoolId, user)

        user.adminLevel = level
        user.touch()
        // Daraja tokendagi guruhda — yangi darajasi bilan qayta kirsin.
        refreshTokens.revokeAllForUser(user.id, "ADMIN_LEVEL_CHANGED")
        record("ADMIN_LEVEL_CHANGED", user, mapOf("from" to previous.name, "to" to level.name))
        return toDto(user)
    }

    @Transactional
    fun setStatus(schoolId: UUID, userId: UUID, status: UserStatus): SchoolAdminDto {
        val user = requireAdmin(schoolId, userId)
        if (status == UserStatus.BLOCKED) {
            if (user.id == currentUser.userId) throw ApiException.validation("O'zingizni bloklay olmaysiz")
            if (user.isSchoolSuperAdmin) assertNotLastSuper(schoolId, user)
            refreshTokens.revokeAllForUser(user.id, "BLOCKED_BY_SCHOOL")
        }
        user.status = status
        user.touch()
        record(if (status == UserStatus.BLOCKED) "USER_BLOCKED" else "USER_UNBLOCKED", user)
        return toDto(user)
    }

    @Transactional
    fun resetPassword(schoolId: UUID, userId: UUID): CredentialsDto {
        val user = requireAdmin(schoolId, userId)
        if (user.id == currentUser.userId) {
            throw ApiException.validation("O'z parolingizni profil sozlamalarida o'zgartiring")
        }
        val password = Credentials.randomPassword(12)
        user.passwordHash = Passwords.hash(password)
        user.mustChangePassword = true
        user.touch()
        refreshTokens.revokeAllForUser(user.id, "PASSWORD_RESET")
        credentialsNotifier.send(user.phone, user.username.orEmpty(), password, schoolId, user.id, user.locale, user.email)
        record("PASSWORD_RESET", user)
        return CredentialsDto(user.username.orEmpty(), password)
    }

    private fun assertNotLastSuper(schoolId: UUID, target: UserAccount) {
        val others = users.countActiveSchoolSuperAdmins(schoolId) - (if (target.status != UserStatus.BLOCKED) 1 else 0)
        if (others <= 0) {
            throw ApiException.validation("Maktabda kamida bitta faol superadmin qolishi kerak. Avval boshqa adminni superadmin qiling.")
        }
    }

    /** Begona maktab admini yoki admin bo'lmagan hisob — 404 (mavjudligini ham tasdiqlamaymiz). */
    private fun requireAdmin(schoolId: UUID, userId: UUID): UserAccount {
        val user = users.findById(userId)
        if (user == null || user.schoolId != schoolId || user.role != Role.SCHOOL_ADMIN) {
            throw ApiException.notFound("Admin", userId)
        }
        return user
    }

    private fun toDto(user: UserAccount) = SchoolAdminDto(
        id = user.id,
        firstName = user.firstName,
        lastName = user.lastName,
        username = user.username,
        phone = user.phone,
        email = user.email,
        level = user.adminLevel ?: SchoolAdminLevel.SUPER,
        status = user.status,
        lastLoginAt = user.lastLoginAt,
        isMe = user.id == currentUser.userIdOrNull,
    )

    private fun record(action: String, target: UserAccount, payload: Map<String, Any?> = emptyMap()) {
        audit.persist(
            AuditEntry().apply {
                this.schoolId = target.schoolId
                this.actorId = currentUser.userIdOrNull
                this.actorRole = runCatching { currentUser.role.name }.getOrNull()
                this.action = action
                this.entityType = "USER"
                this.entityId = target.id.toString()
                this.payload = Json.write(payload)
            },
        )
    }
}
