package uz.farzandim.school.api

import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import uz.farzandim.school.dto.AdminLevelBody
import uz.farzandim.school.dto.CreateSchoolAdminBody
import uz.farzandim.school.dto.CredentialsDto
import uz.farzandim.school.dto.SchoolAdminDto
import uz.farzandim.school.service.SchoolAdminsService
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import java.util.UUID

/**
 * Maktab adminlari.
 *
 * Ro'yxatni har qanday admin ko'radi; o'zgartirish — faqat maktab
 * superadmini (`SCHOOL_SUPER_ADMIN` guruhi, `admin_level = SUPER`).
 * SUPER_ADMIN (platforma) ham `X-School-Id` bilan kira oladi.
 */
@Path("/school-admins")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
class SchoolAdminsResource {

    @Inject lateinit var admins: SchoolAdminsService
    @Inject lateinit var currentUser: CurrentUser

    private val schoolId: UUID get() = currentUser.requireSchoolId()

    @GET
    fun list(): ApiResponse<List<SchoolAdminDto>> = ApiResponse.of(admins.list(schoolId))

    @POST
    @RolesAllowed(Roles.SCHOOL_SUPER_ADMIN, Roles.SUPER_ADMIN)
    fun create(@Valid body: CreateSchoolAdminBody): ApiResponse<CredentialsDto> =
        ApiResponse.of(admins.create(schoolId, body))

    @PATCH @Path("/{id}/level")
    @RolesAllowed(Roles.SCHOOL_SUPER_ADMIN, Roles.SUPER_ADMIN)
    fun level(@PathParam("id") id: UUID, body: AdminLevelBody): ApiResponse<SchoolAdminDto> =
        ApiResponse.of(admins.setLevel(schoolId, id, body.level))

    @POST @Path("/{id}/block")
    @RolesAllowed(Roles.SCHOOL_SUPER_ADMIN, Roles.SUPER_ADMIN)
    fun block(@PathParam("id") id: UUID): ApiResponse<SchoolAdminDto> =
        ApiResponse.of(admins.setStatus(schoolId, id, UserStatus.BLOCKED))

    @POST @Path("/{id}/unblock")
    @RolesAllowed(Roles.SCHOOL_SUPER_ADMIN, Roles.SUPER_ADMIN)
    fun unblock(@PathParam("id") id: UUID): ApiResponse<SchoolAdminDto> =
        ApiResponse.of(admins.setStatus(schoolId, id, UserStatus.ACTIVE))

    @POST @Path("/{id}/reset-password")
    @RolesAllowed(Roles.SCHOOL_SUPER_ADMIN, Roles.SUPER_ADMIN)
    fun resetPassword(@PathParam("id") id: UUID): ApiResponse<CredentialsDto> =
        ApiResponse.of(admins.resetPassword(schoolId, id))
}
