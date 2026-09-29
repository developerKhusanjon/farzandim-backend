package uz.farzandim.school.api

import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import uz.farzandim.school.service.AccessControl
import uz.farzandim.school.service.SchoolService
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.TokenAudience
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.AuthResponse
import uz.farzandim.shared.security.AuthService
import uz.farzandim.shared.security.AuthUserDto
import uz.farzandim.shared.security.ChangePasswordBody
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.LoginRequest
import uz.farzandim.shared.security.RefreshRequest
import uz.farzandim.shared.security.Roles

/**
 * school-backend faqat maktab xodimlarini kiritadi.
 * Ota-ona tokeni bu yerda ishlamaydi — `aud` da `farzandim-mobile` turadi
 * va konfiguratsiyada faqat `farzandim-school` qabul qilinadi.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AuthResource {

    @Inject lateinit var auth: AuthService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var access: AccessControl
    @Inject lateinit var schools: SchoolService

    private val allowedRoles = setOf(Role.SCHOOL_ADMIN, Role.TEACHER, Role.SUPER_ADMIN)

    @POST
    @Path("/login")
    @PermitAll
    fun login(@Valid body: LoginRequest, @Context headers: HttpHeaders): ApiResponse<AuthResponse> =
        ApiResponse.of(auth.login(body, TokenAudience.SCHOOL, allowedRoles, clientIp(headers)))

    @POST
    @Path("/refresh")
    @PermitAll
    fun refresh(@Valid body: RefreshRequest, @Context headers: HttpHeaders): ApiResponse<AuthResponse> =
        ApiResponse.of(auth.refresh(body.refreshToken, TokenAudience.SCHOOL, clientIp(headers)))

    @POST
    @Path("/logout")
    @PermitAll
    fun logout(body: RefreshRequest?): ApiResponse<Map<String, Boolean>> {
        auth.logout(body?.refreshToken)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @GET
    @Path("/me")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
    fun me(): ApiResponse<Map<String, Any?>> {
        val user = users.findById(currentUser.userId)
            ?: throw uz.farzandim.shared.api.ApiException.unauthorized()
        return ApiResponse.of(
            mapOf(
                "user" to AuthUserDto.from(user),
                "schools" to auth.schoolsFor(user),
                "permissions" to permissionsFor(user),
                "teacherId" to access.teacherOrNull()?.id,
            ),
        )
    }

    @POST
    @Path("/password/change")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
    fun changePassword(@Valid body: ChangePasswordBody): ApiResponse<Map<String, Boolean>> {
        auth.changePassword(currentUser.userId, body)
        return ApiResponse.of(mapOf("ok" to true))
    }

    /** Frontend menyuni shu ro'yxatga qarab chizadi; haqiqiy tekshiruv serverda. */
    private fun permissionsFor(user: UserAccount): List<String> = when (currentUser.role) {
        Role.SUPER_ADMIN, Role.SCHOOL_ADMIN -> listOfNotNull(
            // Maktab superadmini — adminlarni boshqaradi
            "admins:manage".takeIf { user.role == Role.SUPER_ADMIN || user.isSchoolSuperAdmin },
            "admins:read",
            "school:read", "school:write",
            "students:read", "students:write",
            "teachers:read", "teachers:write",
            "groups:write", "subjects:write", "schedule:write",
            "grades:write", "attendance:write", "homework:write",
            "reports:read", "messages:send", "ai:use",
        )
        Role.TEACHER -> listOf(
            "school:read", "students:read", "groups:read", "subjects:read",
            "schedule:read", "grades:write", "attendance:write",
            "homework:write", "assessment:write", "messages:send", "ai:use",
        )
        else -> emptyList()
    }

    private fun clientIp(headers: HttpHeaders): String? =
        headers.getHeaderString("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()
            ?: headers.getHeaderString("X-Real-IP")
}
