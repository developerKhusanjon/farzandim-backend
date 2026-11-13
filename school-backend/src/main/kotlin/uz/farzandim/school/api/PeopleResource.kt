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
import uz.farzandim.school.dto.AssignmentBody
import uz.farzandim.school.dto.CredentialsDto
import uz.farzandim.school.dto.InterestBody
import uz.farzandim.school.dto.InterestDto
import uz.farzandim.school.dto.ParentInput
import uz.farzandim.school.dto.StudentBody
import uz.farzandim.school.dto.StudentCreatedDto
import uz.farzandim.school.dto.StudentDto
import uz.farzandim.school.dto.TeacherBody
import uz.farzandim.school.dto.TeacherDto
import uz.farzandim.school.service.AssessmentService
import uz.farzandim.school.service.JournalService
import uz.farzandim.school.service.PeopleService
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.domain.StudentStatus
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import java.time.LocalDate
import java.util.UUID

@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class PeopleResource {

    @Inject lateinit var people: PeopleService
    @Inject lateinit var assessment: AssessmentService
    @Inject lateinit var journal: JournalService
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var currentUser: CurrentUser

    private val schoolId: UUID get() = currentUser.requireSchoolId()

    /* ------------------------------- O'quvchilar ---------------------------- */

    @GET @Path("/students")
    fun list(
        @QueryParam("q") q: String?,
        @QueryParam("groupId") groupId: UUID?,
        @QueryParam("gradeLevel") gradeLevel: Int?,
        @QueryParam("status") status: StudentStatus?,
        @QueryParam("needsAttention") needsAttention: Boolean?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("sort") sort: String?,
    ): ApiResponse<List<StudentDto>> =
        ApiResponse.paged(
            people.listStudents(schoolId, q, groupId, gradeLevel, status, needsAttention, PageRequest.of(page, size, sort)),
        )

    @GET @Path("/students/{id}")
    fun get(@PathParam("id") id: UUID): ApiResponse<StudentDto> = ApiResponse.of(people.getStudent(schoolId, id))

    @POST @Path("/students")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun create(@Valid body: StudentBody): ApiResponse<StudentCreatedDto> =
        ApiResponse.of(people.createStudent(schoolId, body))

    @PATCH @Path("/students/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun update(@PathParam("id") id: UUID, @Valid body: StudentBody): ApiResponse<StudentDto> =
        ApiResponse.of(people.updateStudent(schoolId, id, body))

    @DELETE @Path("/students/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun archive(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        people.archiveStudent(schoolId, id)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @POST @Path("/students/{id}/parents")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun addParent(@PathParam("id") id: UUID, @Valid body: ParentInput): ApiResponse<CredentialsDto> {
        val student = students.requireInSchool(id, schoolId, "O'quvchi")
        return ApiResponse.of(people.attachParent(schoolId, student, body))
    }

    /* ------------------------ O'quvchi bo'yicha tahlil ---------------------- */

    @GET @Path("/students/{id}/performance")
    fun performance(
        @PathParam("id") id: UUID,
        @QueryParam("period") period: PerformancePeriod?,
    ) = ApiResponse.of(assessment.performance(schoolId, id, period ?: PerformancePeriod.MONTH))

    @GET @Path("/students/{id}/subjects/{subjectId}")
    fun subjectDetail(@PathParam("id") id: UUID, @PathParam("subjectId") subjectId: UUID) =
        ApiResponse.of(assessment.subjectDetail(schoolId, id, subjectId))

    @GET @Path("/students/{id}/grades")
    fun grades(
        @PathParam("id") id: UUID,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ) = ApiResponse.of(
        journal.listGrades(schoolId, id, from?.let(LocalDate::parse), to?.let(LocalDate::parse)),
    )

    /** O'quvchi davomati, standart — oxirgi 60 kun */
    @GET @Path("/students/{id}/attendance")
    fun attendance(
        @PathParam("id") id: UUID,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ) = ApiResponse.of(
        journal.listStudentAttendance(schoolId, id, from?.let(LocalDate::parse), to?.let(LocalDate::parse)),
    )

    @GET @Path("/students/{id}/skills")
    fun skills(@PathParam("id") id: UUID) = ApiResponse.of(assessment.listSkills(schoolId, id))

    @GET @Path("/students/{id}/achievements")
    fun achievements(@PathParam("id") id: UUID) = ApiResponse.of(assessment.listAchievements(schoolId, id))

    @GET @Path("/students/{id}/interests")
    fun interests(@PathParam("id") id: UUID): ApiResponse<List<InterestDto>> =
        ApiResponse.of(assessment.listInterests(schoolId, id))

    @POST @Path("/students/{id}/interests")
    fun upsertInterest(@PathParam("id") id: UUID, @Valid body: InterestBody): ApiResponse<InterestDto> =
        ApiResponse.of(assessment.upsertInterest(schoolId, id, body))

    @GET @Path("/students/{id}/recommendations")
    fun recommendations(@PathParam("id") id: UUID) =
        ApiResponse.of(assessment.listRecommendations(schoolId, id))

    /* ------------------------------ O'qituvchilar --------------------------- */

    @GET @Path("/teachers")
    fun listTeachers(): ApiResponse<List<TeacherDto>> = ApiResponse.of(people.listTeachers(schoolId))

    @GET @Path("/teachers/{id}")
    fun getTeacher(@PathParam("id") id: UUID): ApiResponse<TeacherDto> =
        ApiResponse.of(people.getTeacher(schoolId, id))

    @POST @Path("/teachers")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun createTeacher(@Valid body: TeacherBody): ApiResponse<Map<String, Any?>> {
        val (teacher, credentials) = people.createTeacher(schoolId, body)
        return ApiResponse.of(mapOf("teacher" to teacher, "credentials" to credentials))
    }

    @POST @Path("/teachers/{id}/credentials")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun resetCredentials(@PathParam("id") id: UUID): ApiResponse<CredentialsDto> =
        ApiResponse.of(people.resetTeacherCredentials(schoolId, id))

    @POST @Path("/teachers/{id}/assignments")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun assign(@PathParam("id") id: UUID, body: AssignmentBody): ApiResponse<Map<String, Boolean>> {
        people.assignTeacher(schoolId, id, body.groupId, body.subjectId)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @DELETE @Path("/teachers/{id}/assignments")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun unassign(
        @PathParam("id") id: UUID,
        @QueryParam("groupId") groupId: UUID,
        @QueryParam("subjectId") subjectId: UUID,
    ): ApiResponse<Map<String, Boolean>> {
        people.removeAssignment(schoolId, id, groupId, subjectId)
        return ApiResponse.of(mapOf("ok" to true))
    }
}
