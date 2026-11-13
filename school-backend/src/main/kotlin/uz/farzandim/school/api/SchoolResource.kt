package uz.farzandim.school.api

import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import uz.farzandim.school.dto.GroupBody
import uz.farzandim.school.dto.GroupDto
import uz.farzandim.school.dto.SchoolDto
import uz.farzandim.school.dto.SchoolStatsDto
import uz.farzandim.school.dto.SubjectBody
import uz.farzandim.school.dto.SubjectDto
import uz.farzandim.school.dto.TopicBody
import uz.farzandim.school.dto.TopicDto
import uz.farzandim.school.dto.UpdateSchoolBody
import uz.farzandim.school.service.PeopleService
import uz.farzandim.school.service.SchoolService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import uz.farzandim.shared.analytics.SubjectScoreDto
import java.util.UUID

@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class SchoolResource {

    @Inject lateinit var schools: SchoolService
    @Inject lateinit var people: PeopleService
    @Inject lateinit var currentUser: CurrentUser

    /** URL'dagi id joriy maktab bilan mos kelishi shart — boshqasini so'rash 404. */
    private fun scoped(id: UUID): UUID {
        val school = currentUser.requireSchoolId()
        if (id != school && !currentUser.isSuperAdmin) throw ApiException.notFound("Maktab", id)
        return if (currentUser.isSuperAdmin) id else school
    }

    @GET @Path("/schools/{id}")
    fun get(@PathParam("id") id: UUID): ApiResponse<SchoolDto> = ApiResponse.of(schools.get(scoped(id)))

    @PATCH @Path("/schools/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun update(@PathParam("id") id: UUID, body: UpdateSchoolBody): ApiResponse<SchoolDto> =
        ApiResponse.of(schools.update(scoped(id), body))

    @GET @Path("/schools/{id}/stats")
    fun stats(@PathParam("id") id: UUID): ApiResponse<SchoolStatsDto> =
        ApiResponse.of(schools.stats(scoped(id)))

    @GET @Path("/schools/{id}/subject-averages")
    fun subjectAverages(@PathParam("id") id: UUID): ApiResponse<List<SubjectScoreDto>> =
        ApiResponse.of(schools.subjectAverages(scoped(id)))

    /* ---------------------------------- Fanlar ------------------------------ */

    @GET @Path("/subjects")
    fun listSubjects(): ApiResponse<List<SubjectDto>> =
        ApiResponse.of(schools.listSubjects(currentUser.requireSchoolId()))

    @POST @Path("/subjects")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun createSubject(@Valid body: SubjectBody): ApiResponse<SubjectDto> =
        ApiResponse.of(schools.createSubject(currentUser.requireSchoolId(), body))

    @PATCH @Path("/subjects/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun updateSubject(@PathParam("id") id: UUID, @Valid body: SubjectBody): ApiResponse<SubjectDto> =
        ApiResponse.of(schools.updateSubject(currentUser.requireSchoolId(), id, body))

    @DELETE @Path("/subjects/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun archiveSubject(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        schools.archiveSubject(currentUser.requireSchoolId(), id)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @GET @Path("/subjects/{id}/topics")
    fun listTopics(
        @PathParam("id") id: UUID,
        @QueryParam("gradeLevel") gradeLevel: Int?,
    ): ApiResponse<List<TopicDto>> =
        ApiResponse.of(schools.listTopics(currentUser.requireSchoolId(), id, gradeLevel))

    @POST @Path("/subjects/{id}/topics")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun createTopic(@PathParam("id") id: UUID, @Valid body: TopicBody): ApiResponse<TopicDto> =
        ApiResponse.of(schools.createTopic(currentUser.requireSchoolId(), id, body))

    /* --------------------------------- Guruhlar ----------------------------- */

    @GET @Path("/groups")
    fun listGroups(): ApiResponse<List<GroupDto>> =
        ApiResponse.of(schools.listGroups(currentUser.requireSchoolId()))

    @GET @Path("/groups/{id}")
    fun getGroup(@PathParam("id") id: UUID): ApiResponse<GroupDto> =
        ApiResponse.of(schools.getGroup(currentUser.requireSchoolId(), id))

    @POST @Path("/groups")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun createGroup(@Valid body: GroupBody): ApiResponse<GroupDto> =
        ApiResponse.of(schools.createGroup(currentUser.requireSchoolId(), body))

    @PATCH @Path("/groups/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun updateGroup(@PathParam("id") id: UUID, @Valid body: GroupBody): ApiResponse<GroupDto> =
        ApiResponse.of(schools.updateGroup(currentUser.requireSchoolId(), id, body))

    @DELETE @Path("/groups/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun archiveGroup(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        schools.archiveGroup(currentUser.requireSchoolId(), id)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @GET @Path("/groups/{id}/students")
    fun groupStudents(@PathParam("id") id: UUID): ApiResponse<List<uz.farzandim.school.dto.StudentDto>> {
        val schoolId = currentUser.requireSchoolId()
        val page = uz.farzandim.shared.api.PageRequest.of(1, 200, null)
        return ApiResponse.of(people.listStudents(schoolId, null, id, null, null, null, page).items)
    }
}
