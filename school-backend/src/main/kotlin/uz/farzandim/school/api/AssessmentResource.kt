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
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import uz.farzandim.school.dto.AssessmentGenerateBody
import uz.farzandim.school.dto.AssessmentTaskBody
import uz.farzandim.school.dto.AssessmentTaskDto
import uz.farzandim.school.dto.ObservationsBody
import uz.farzandim.school.dto.RecommendationDto
import uz.farzandim.school.dto.RecommendationStatusBody
import uz.farzandim.school.service.AiFeatureService
import uz.farzandim.school.service.AssessmentService
import uz.farzandim.school.service.Mappers
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import java.util.UUID

@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class AssessmentResource {

    @Inject lateinit var assessment: AssessmentService
    @Inject lateinit var aiFeatures: AiFeatureService
    @Inject lateinit var mappers: Mappers
    @Inject lateinit var currentUser: CurrentUser

    private val schoolId: UUID get() = currentUser.requireSchoolId()

    @GET @Path("/assessment-tasks")
    fun list(@QueryParam("groupId") groupId: UUID?): ApiResponse<List<AssessmentTaskDto>> =
        ApiResponse.of(assessment.listTasks(schoolId, groupId))

    @POST @Path("/assessment-tasks")
    fun create(@Valid body: AssessmentTaskBody): ApiResponse<AssessmentTaskDto> =
        ApiResponse.of(assessment.createTask(schoolId, body))

    /** AI sinfda bajariladigan topshiriq tuzadi (docs/05-ai-prompts.md, 6-bo'lim). */
    @POST @Path("/assessment-tasks/generate")
    fun generate(@Valid body: AssessmentGenerateBody): ApiResponse<AssessmentTaskDto> =
        ApiResponse.of(mappers.assessmentTask(aiFeatures.generateAssessmentTask(schoolId, body)))

    @POST @Path("/assessment-tasks/{id}/observations")
    fun addObservations(
        @PathParam("id") id: UUID,
        body: ObservationsBody,
    ): ApiResponse<AssessmentTaskDto> = ApiResponse.of(assessment.addObservations(schoolId, id, body))

    /** Kuzatuvlarni tahlil qilib, ko'nikma profillarini yozadi. */
    @POST @Path("/assessment-tasks/{id}/analyze")
    fun analyze(@PathParam("id") id: UUID): ApiResponse<AssessmentTaskDto> =
        ApiResponse.of(mappers.assessmentTask(aiFeatures.analyzeAssessmentTask(schoolId, id)))

    /* -------------------------------- Tavsiyalar ---------------------------- */

    @GET @Path("/recommendations")
    fun recommendations(@QueryParam("studentId") studentId: UUID): ApiResponse<List<RecommendationDto>> =
        ApiResponse.of(assessment.listRecommendations(schoolId, studentId))

    @POST @Path("/recommendations/generate")
    fun generateRecommendations(@QueryParam("studentId") studentId: UUID): ApiResponse<List<RecommendationDto>> =
        ApiResponse.of(aiFeatures.generateRecommendations(schoolId, studentId))

    @PATCH @Path("/recommendations/{id}")
    fun updateStatus(
        @PathParam("id") id: UUID,
        body: RecommendationStatusBody,
    ): ApiResponse<RecommendationDto> =
        ApiResponse.of(assessment.updateRecommendationStatus(schoolId, id, body.status))
}
