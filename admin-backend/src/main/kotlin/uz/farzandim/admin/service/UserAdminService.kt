package uz.farzandim.admin.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.admin.dto.CreateTeamMemberBody
import uz.farzandim.admin.dto.CredentialsDto
import uz.farzandim.admin.dto.PlatformUserDto
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.Page
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.SchoolAdminLevel
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.notify.CredentialsNotifier
import uz.farzandim.shared.notify.EmailAddresses
import uz.farzandim.shared.notify.PhoneNumbers
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Passwords
import uz.farzandim.shared.security.RefreshTokenService
import uz.farzandim.shared.util.Credentials
import java.util.UUID

/**
 * Hisoblar: butun platforma bo'yicha qidiruv, bloklash, parol, daraja
 * va platforma jamoasi (super adminlar).
 */
@ApplicationScoped
class UserAdminService {

    @Inject lateinit var users: UserRepository
    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var refreshTokens: RefreshTokenService
    @Inject lateinit var credentialsNotifier: CredentialsNotifier
    @Inject lateinit var auditRecorder: AuditRecorder
    @Inject lateinit var currentUser: CurrentUser

    fun search(q: String?, role: Role?, schoolId: UUID?, status: UserStatus?, page: PageRequest): Page<PlatformUserDto> {
        val conditions = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any>()
        if (!q.isNullOrBlank()) {
            // `search_text` — ism, login, email, telefon (V5, pg_trgm indeksi)
            conditions += "searchText like :q"
            params["q"] = "%${q.trim().lowercase()}%"
        }
        role?.let {
            conditions += "role = :role"
            params["role"] = it
        }
        schoolId?.let {
            conditions += "schoolId = :school"
            params["school"] = it
        }
        status?.let {
            conditions += "status = :status"
            params["status"] = it
        }
        val query = users.find("${conditions.joinToString(" and ")} order by lastName, firstName", params)
        val total = query.count()
        val list = query.page(page.page - 1, page.size).list()
        return Page(withSchoolNames(list), page.page, page.size, total)
    }

    fun get(id: UUID): PlatformUserDto = withSchoolNames(listOf(require(id))).first()

    @Transactional
    fun setStatus(userId: UUID, status: UserStatus): PlatformUserDto {
        val user = require(userId)
        if (status == UserStatus.BLOCKED) {
            if (userId == currentUser.userIdOrNull) throw ApiException.validation("O'zingizni bloklay olmaysiz")
            if (user.role == Role.SUPER_ADMIN && activeSuperAdmins() <= 1) {
                throw ApiException.validation("Oxirgi faol super adminni bloklab bo'lmaydi")
            }
        }
        user.status = status
        user.touch()
        val revoked = if (status == UserStatus.BLOCKED) refreshTokens.revokeAllForUser(userId, "BLOCKED_BY_ADMIN") else 0
        auditRecorder.record(
            if (status == UserStatus.BLOCKED) "USER_BLOCKED" else "USER_UNBLOCKED",
            "USER", userId.toString(), user.schoolId,
            mapOf("role" to user.role.name, "sessionsRevoked" to revoked),
        )
        return get(userId)
    }

    @Transactional
    fun resetPassword(userId: UUID): CredentialsDto {
        val user = require(userId)
        if (userId == currentUser.userIdOrNull) {
            throw ApiException.validation("O'z parolingizni Profil sahifasida o'zgartiring")
        }
        val password = Credentials.randomPassword(12)
        user.passwordHash = Passwords.hash(password)
        user.mustChangePassword = true
        user.touch()
        refreshTokens.revokeAllForUser(userId, "PASSWORD_RESET")
        credentialsNotifier.send(user.phone, user.username.orEmpty(), password, user.schoolId, user.id, user.locale, user.email)
        auditRecorder.record("PASSWORD_RESET", "USER", userId.toString(), user.schoolId, mapOf("role" to user.role.name))
        return CredentialsDto(user.username ?: user.email.orEmpty(), password, user.id)
    }

    @Transactional
    fun revokeSessions(userId: UUID): Int {
        val user = require(userId)
        val revoked = refreshTokens.revokeAllForUser(userId, "REVOKED_BY_ADMIN")
        auditRecorder.record("SESSIONS_REVOKED", "USER", userId.toString(), user.schoolId, mapOf("revoked" to revoked))
        return revoked
    }

    /**
     * Daraja tokenga guruh sifatida yoziladi — o'zgarish darhol kuchga
     * kirishi uchun foydalanuvchining sessiyalari yopiladi.
     */
    @Transactional
    fun setAdminLevel(userId: UUID, level: SchoolAdminLevel): PlatformUserDto {
        val user = require(userId)
        if (user.role != Role.SCHOOL_ADMIN) throw ApiException.validation("Daraja faqat maktab admini uchun")
        val previous = user.adminLevel ?: SchoolAdminLevel.SUPER
        if (previous == level) return get(userId)

        user.adminLevel = level
        user.touch()
        refreshTokens.revokeAllForUser(userId, "ADMIN_LEVEL_CHANGED")
        auditRecorder.record(
            "ADMIN_LEVEL_CHANGED", "USER", userId.toString(), user.schoolId,
            mapOf("from" to previous.name, "to" to level.name),
        )
        return get(userId)
    }

    /* ------------------------------ Jamoa ------------------------------ */

    fun team(): List<PlatformUserDto> =
        users.list("role = ?1 order by createdAt", Role.SUPER_ADMIN).map { AdminMappers.user(it) }

    @Transactional
    fun createTeamMember(body: CreateTeamMemberBody): CredentialsDto {
        val email = EmailAddresses.normalize(body.email)
        if (!EmailAddresses.isValid(email)) throw ApiException.validation("Email noto'g'ri", mapOf("email" to body.email))
        if (users.count("lower(email) = ?1", email) > 0) throw ApiException.conflict("Bu email allaqachon ro'yxatdan o'tgan")
        val phone = body.phone?.takeIf { it.isNotBlank() }?.let(PhoneNumbers::e164)
        if (phone != null && users.count("phone", phone) > 0) {
            throw ApiException.conflict("Bu telefon raqami allaqachon ro'yxatdan o'tgan")
        }

        val username = Credentials.suggestUsername(body.firstName, body.lastName) { users.usernameTaken(it) }
        val password = Credentials.randomPassword(14)
        val user = UserAccount().apply {
            this.schoolId = null
            this.role = Role.SUPER_ADMIN
            this.firstName = body.firstName.trim()
            this.lastName = body.lastName.trim()
            this.username = username
            this.email = email
            this.phone = phone
            this.passwordHash = Passwords.hash(password)
            this.status = UserStatus.INVITED
            this.mustChangePassword = true
        }
        users.persist(user)
        auditRecorder.record("TEAM_MEMBER_CREATED", "USER", user.id.toString(), null, mapOf("username" to username))
        return CredentialsDto(username, password, user.id)
    }

    private fun activeSuperAdmins(): Long = users.count("role = ?1 and status <> ?2", Role.SUPER_ADMIN, UserStatus.BLOCKED)

    private fun require(id: UUID): UserAccount = users.findById(id) ?: throw ApiException.notFound("Foydalanuvchi", id)

    private fun withSchoolNames(list: List<UserAccount>): List<PlatformUserDto> {
        val ids = list.mapNotNull { it.schoolId }.toSet()
        val names = if (ids.isEmpty()) emptyMap() else schools.list("id in ?1", ids).associate { it.id to it.name }
        return list.map { AdminMappers.user(it, it.schoolId?.let(names::get)) }
    }
}
