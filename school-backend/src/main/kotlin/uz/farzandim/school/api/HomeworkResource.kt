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
import uz.farzandim.school.dto.HomeworkBody
import uz.farzandim.school.dto.HomeworkDraftDto
import uz.farzandim.school.dto.HomeworkDto
import uz.farzandim.school.dto.HomeworkGenerateBody
import uz.farzandim.school.dto.HomeworkSubmissionDto
import uz.farzandim.school.dto.HomeworkVariantBody
import uz.farzandim.school.dto.HomeworkVariantDto
import uz.farzandim.school.dto.SubmissionUpdateBody
import uz.farzandim.school.service.AiFeatureService
import uz.farzandim.school.service.HomeworkService
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import java.util.UUID

@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class HomeworkResource {

    @Inject lateinit var homework: HomeworkService
    @Inject lateinit var aiFeatures: AiFeatureService
    @Inject lateinit var currentUser: CurrentUser

    private val schoolId: UUID get() = currentUser.requireSchoolId()

    @GET @Path("/homework")
    fun list(
        @QueryParam("scope") scope: String?,
        @QueryParam("groupId") groupId: UUID?,
        @QueryParam("studentId") studentId: UUID?,
    ): ApiResponse<List<HomeworkDto>> = ApiResponse.of(homework.list(schoolId, scope, groupId, studentId))

    @GET @Path("/homework/{id}")
    fun get(@PathParam("id") id: UUID): ApiResponse<HomeworkDto> = ApiResponse.of(homework.get(schoolId, id))

    @POST @Path("/homework")
    fun create(@Valid body: HomeworkBody): ApiResponse<HomeworkDto> =
        ApiResponse.of(homework.create(schoolId, body))

    @PATCH @Path("/homework/{id}")
    fun update(@PathParam("id") id: UUID, @Valid body: HomeworkBody): ApiResponse<HomeworkDto> =
        ApiResponse.of(homework.update(schoolId, id, body))

    @DELETE @Path("/homework/{id}")
    fun delete(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        homework.delete(schoolId, id)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @POST @Path("/homework/{id}/send")
    fun send(@PathParam("id") id: UUID): ApiResponse<HomeworkDto> = ApiResponse.of(homework.send(schoolId, id))

    /* --------------------------------- AI ----------------------------------- */

    /** Vazifa matnini AI tuzadi. Saqlanmaydi — o'qituvchi tahrirlab, keyin yaratadi. */
    @POST @Path("/homework/generate")
    fun generate(@Valid body: HomeworkGenerateBody): ApiResponse<HomeworkDraftDto> =
        ApiResponse.of(aiFeatures.generateHomework(schoolId, body))

    @POST @Path("/homework/{id}/parent-hint")
    fun parentHint(@PathParam("id") id: UUID): ApiResponse<Map<String, String>> =
        ApiResponse.of(mapOf("parentHint" to aiFeatures.generateParentHint(schoolId, id)))

    /** Qiynalayotganlarni aniqlab, soddalashtirilgan variant TAKLIF qiladi. */
    @POST @Path("/homework/{id}/variants/suggest")
    fun suggestVariants(@PathParam("id") id: UUID): ApiResponse<List<HomeworkVariantBody>> =
        ApiResponse.of(aiFeatures.suggestVariants(schoolId, id))

    /* ------------------------------- Variantlar ------------------------------ */

    @GET @Path("/homework/{id}/variants")
    fun variants(@PathParam("id") id: UUID): ApiResponse<List<HomeworkVariantDto>> =
        ApiResponse.of(homework.listVariants(schoolId, id))

    /** O'qituvchi tasdiqlagan variantni saqlash va o'quvchiga biriktirish. */
    @POST @Path("/homework/{id}/variants")
    fun saveVariant(
        @PathParam("id") id: UUID,
        @Valid body: HomeworkVariantBody,
        @QueryParam("aiGenerated") aiGenerated: Boolean?,
    ): ApiResponse<HomeworkVariantDto> =
        ApiResponse.of(homework.saveVariant(schoolId, id, body, aiGenerated ?: true))

    /* ------------------------------- Topshiriqlar ---------------------------- */

    @GET @Path("/homework/{id}/submissions")
    fun submissions(@PathParam("id") id: UUID): ApiResponse<List<HomeworkSubmissionDto>> =
        ApiResponse.of(homework.listSubmissions(schoolId, id))

    @PATCH @Path("/submissions/{id}")
    fun updateSubmission(
        @PathParam("id") id: UUID,
        body: SubmissionUpdateBody,
    ): ApiResponse<HomeworkSubmissionDto> = ApiResponse.of(homework.updateSubmission(schoolId, id, body))
}
