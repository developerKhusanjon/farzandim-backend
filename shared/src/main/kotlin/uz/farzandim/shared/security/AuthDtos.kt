package uz.farzandim.shared.security

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uz.farzandim.shared.domain.Locale
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.SchoolAdminLevel
import uz.farzandim.shared.domain.UserAccount
import java.time.Instant
import java.util.UUID

data class LoginRequest(
    @field:NotBlank(message = "login bo'sh bo'lmasin")
    val login: String = "",
    @field:NotBlank(message = "parol bo'sh bo'lmasin")
    val password: String = "",
    val deviceInfo: String? = null,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String = "",
)

data class OtpRequestBody(
    val phone: String? = null,
    val email: String? = null,
)

data class OtpVerifyBody(
    val phone: String? = null,
    val email: String? = null,
    @field:NotBlank
    val code: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val deviceInfo: String? = null,
)

data class ChangePasswordBody(
    @field:NotBlank val currentPassword: String = "",
    @field:NotBlank @field:Size(min = 6) val newPassword: String = "",
)

data class AuthUserDto(
    val id: UUID,
    val schoolId: UUID?,
    val role: Role,
    val firstName: String,
    val lastName: String,
    val middleName: String?,
    val avatarUrl: String?,
    val username: String?,
    val email: String?,
    val phone: String?,
    val locale: Locale,
    val mustChangePassword: Boolean,
    /** Faqat maktab admini uchun: SUPER (maktab superadmini) yoki REGULAR. */
    val adminLevel: SchoolAdminLevel? = null,
) {
    companion object {
        fun from(user: UserAccount) = AuthUserDto(
            id = user.id,
            schoolId = user.schoolId,
            role = user.role,
            firstName = user.firstName,
            lastName = user.lastName,
            middleName = user.middleName,
            avatarUrl = user.avatarUrl,
            username = user.username,
            email = user.email,
            phone = user.phone,
            locale = user.locale,
            mustChangePassword = user.mustChangePassword,
            adminLevel = if (user.role == Role.SCHOOL_ADMIN) user.adminLevel ?: SchoolAdminLevel.SUPER else null,
        )
    }
}

data class SchoolBriefDto(
    val id: UUID,
    val name: String,
    val shortName: String,
    val logoUrl: String?,
    val academicYear: String,
)

/** docs/03-api-contract.md — `POST /auth/login` javobi. */
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val expiresAt: Instant,
    val user: AuthUserDto,
    val schools: List<SchoolBriefDto>,
)

data class OtpRequestResponse(
    val sent: Boolean,
    val channel: String,
    val expiresInSeconds: Int,
    /** Faqat dev rejimda to'ldiriladi — SMS shlyuzi ulanmaguncha. */
    val debugCode: String? = null,
)
