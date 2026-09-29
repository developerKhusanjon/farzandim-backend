package uz.farzandim.school.api

import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import uz.farzandim.school.dto.AiConversationBody
import uz.farzandim.school.dto.AiConversationDto
import uz.farzandim.school.dto.AiMessageBody
import uz.farzandim.school.dto.AiMessageDto
import uz.farzandim.school.dto.AiSearchBody
import uz.farzandim.school.service.AiChatService
import uz.farzandim.shared.ai.AiJobService
import uz.farzandim.shared.ai.JobView
import uz.farzandim.shared.ai.SemanticHit
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import java.util.UUID

@Path("/ai")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class AiResource {

    @Inject lateinit var chat: AiChatService
    @Inject lateinit var jobs: AiJobService
    @Inject lateinit var currentUser: CurrentUser

    private val schoolId: UUID get() = currentUser.requireSchoolId()

    @GET @Path("/conversations")
    fun list(): ApiResponse<List<AiConversationDto>> = ApiResponse.of(chat.list())

    @POST @Path("/conversations")
    fun create(body: AiConversationBody): ApiResponse<AiConversationDto> =
        ApiResponse.of(chat.create(schoolId, body))

    @GET @Path("/conversations/{id}")
    fun messages(@PathParam("id") id: UUID): ApiResponse<List<AiMessageDto>> =
        ApiResponse.of(chat.messages(id))

    @POST @Path("/conversations/{id}/messages")
    fun ask(@PathParam("id") id: UUID, @Valid body: AiMessageBody): ApiResponse<AiMessageDto> =
        ApiResponse.of(chat.ask(schoolId, id, body))

    @GET @Path("/suggestions")
    fun suggestions(@QueryParam("context") context: String?): ApiResponse<List<String>> =
        ApiResponse.of(chat.suggestions(context))

    /**
     * Semantik qidiruv — ODDIY qidiruvdan alohida.
     * Ism/telefon uchun `/students?q=` ishlatiladi (pg_trgm).
     */
    @POST @Path("/search")
    fun search(@Valid body: AiSearchBody): ApiResponse<List<SemanticHit>> =
        ApiResponse.of(chat.semanticSearch(schoolId, body))

    /** Fonda ketgan AI ishining holati. */
    @GET @Path("/jobs/{id}")
    fun job(@PathParam("id") id: UUID): ApiResponse<JobView> = ApiResponse.of(jobs.view(id, schoolId))
}
