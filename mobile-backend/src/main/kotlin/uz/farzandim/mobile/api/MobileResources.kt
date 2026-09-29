package uz.farzandim.mobile.api

import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Application
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import uz.farzandim.mobile.dto.AbilityDto
import uz.farzandim.mobile.dto.AiChatBody
import uz.farzandim.mobile.dto.AiHomeworkBody
import uz.farzandim.mobile.dto.AiHomeworkResultDto
import uz.farzandim.mobile.dto.AiMessageDto
import uz.farzandim.mobile.dto.CharacterGroupDto
import uz.farzandim.mobile.dto.ChildDto
import uz.farzandim.mobile.dto.CognitiveTaskDto
import uz.farzandim.mobile.dto.DashboardDto
import uz.farzandim.mobile.dto.DeviceBody
import uz.farzandim.mobile.dto.HomeworkDto
import uz.farzandim.mobile.dto.HomeworkStatusBody
import uz.farzandim.mobile.dto.InterestDto
import uz.farzandim.mobile.dto.LessonSummaryDto
import uz.farzandim.mobile.dto.MessageDto
import uz.farzandim.mobile.dto.ProgressTrendDto
import uz.farzandim.mobile.dto.RecommendationStatusBody
import uz.farzandim.mobile.dto.ScheduleItemDto
import uz.farzandim.mobile.dto.SchoolBriefDto
import uz.farzandim.mobile.dto.StrengthWeaknessDto
import uz.farzandim.mobile.dto.SubjectDetailDto
import uz.farzandim.mobile.dto.SubjectScoreDto
import uz.farzandim.mobile.dto.TeacherDto
import uz.farzandim.mobile.service.ParentAiService
import uz.farzandim.mobile.service.ParentService
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.TokenAudience
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.platform.PlanAccess
import uz.farzandim.shared.platform.PlanFeatures
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.AuthResponse
import uz.farzandim.shared.security.AuthService
import uz.farzandim.shared.security.AuthUserDto
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.LoginRequest
import uz.farzandim.shared.security.OtpRequestBody
import uz.farzandim.shared.security.OtpRequestResponse
import uz.farzandim.shared.security.OtpVerifyBody
import uz.farzandim.shared.security.RefreshRequest
import uz.farzandim.shared.security.Roles
import uz.farzandim.shared.util.Json
import java.util.UUID

@ApplicationPath("/api/v1")
class MobileApplication : Application()

/**
 * Ota-ona autentifikatsiyasi.
 *
 * Ikki yo'l (docs/00-product-overview.md):
 *   1. Maktab bergan login/parol — maktabga bog'langan ota-ona
 *   2. Telefon/email + OTP — mustaqil foydalanuvchi (B2C), faqat AI
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MobileAuthResource {

    @Inject lateinit var auth: AuthService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var schoolRepository: SchoolRepository
    @Inject lateinit var planAccess: PlanAccess

    private val allowedRoles = setOf(Role.PARENT, Role.STUDENT)

    @POST @Path("/login") @PermitAll
    fun login(@Valid body: LoginRequest, @Context headers: HttpHeaders): ApiResponse<AuthResponse> =
        ApiResponse.of(auth.login(body, TokenAudience.MOBILE, allowedRoles, clientIp(headers)))

    @POST @Path("/login/otp/request") @PermitAll
    fun requestOtp(body: OtpRequestBody): ApiResponse<OtpRequestResponse> =
        ApiResponse.of(auth.requestOtp(body))

    @POST @Path("/login/otp/verify") @PermitAll
    fun verifyOtp(@Valid body: OtpVerifyBody, @Context headers: HttpHeaders): ApiResponse<AuthResponse> =
        ApiResponse.of(auth.verifyOtp(body, TokenAudience.MOBILE, clientIp(headers)))

    @POST @Path("/refresh") @PermitAll
    fun refresh(@Valid body: RefreshRequest, @Context headers: HttpHeaders): ApiResponse<AuthResponse> =
        ApiResponse.of(auth.refresh(body.refreshToken, TokenAudience.MOBILE, clientIp(headers)))

    @POST @Path("/logout") @PermitAll
    fun logout(body: RefreshRequest?): ApiResponse<Map<String, Boolean>> {
        auth.logout(body?.refreshToken)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @GET @Path("/me") @RolesAllowed(Roles.PARENT, Roles.STUDENT)
    fun me(): ApiResponse<Map<String, Any?>> {
        val user = users.findById(currentUser.userId)
            ?: throw uz.farzandim.shared.api.ApiException.unauthorized()
        return ApiResponse.of(
            mapOf(
                "user" to AuthUserDto.from(user),
                "schools" to auth.schoolsFor(user),
                "independent" to (user.schoolId == null),
                "features" to featuresFor(user),
            ),
        )
    }

    /**
     * Ilova qaysi AI tugmalarini ko'rsatsin. Server baribir tekshiradi
     * (FEATURE_NOT_AVAILABLE) — bu faqat ota-onaga ishlamaydigan tugma
     * ko'rsatmaslik uchun. Mustaqil ota-ona (B2C) — faqat AI uchun keladi.
     */
    private fun featuresFor(user: UserAccount): Map<String, Boolean> {
        val schoolId = user.schoolId ?: return mapOf("ai" to true, "aiChat" to true)
        val school = schoolRepository.findById(schoolId) ?: return mapOf("ai" to false, "aiChat" to false)
        val plan = planAccess.features(school)
        val enabledBySchool = Json.obj(school.settings).get("aiAssistantEnabled")?.asBoolean(true) ?: true
        val ai = plan[PlanFeatures.AI] == true && enabledBySchool
        return mapOf("ai" to ai, "aiChat" to (ai && plan[PlanFeatures.AI_CHAT] == true))
    }

    private fun clientIp(headers: HttpHeaders): String? =
        headers.getHeaderString("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()
            ?: headers.getHeaderString("X-Real-IP")
}

/** Ota-onaning farzandlari va ularga tegishli ma'lumot. */
@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PARENT)
class ParentResource {

    @Inject lateinit var parents: ParentService

    @GET @Path("/parent/children")
    fun children(): ApiResponse<List<ChildDto>> = ApiResponse.of(parents.children())

    @GET @Path("/parent/children/{childId}/dashboard")
    fun dashboard(@PathParam("childId") childId: UUID): ApiResponse<DashboardDto> =
        ApiResponse.of(parents.dashboard(childId))

    @GET @Path("/parent/children/{childId}/homework")
    fun homework(
        @PathParam("childId") childId: UUID,
        @QueryParam("scope") scope: String?,
    ): ApiResponse<List<HomeworkDto>> = ApiResponse.of(parents.homeworkList(childId, scope))

    @GET @Path("/parent/school")
    fun school(): ApiResponse<SchoolBriefDto> = ApiResponse.of(parents.school())

    @GET @Path("/parent/teachers")
    fun teachers(): ApiResponse<List<TeacherDto>> = ApiResponse.of(parents.teachers())

    @GET @Path("/parent/schedule")
    fun schedule(@QueryParam("childId") childId: UUID?): ApiResponse<List<ScheduleItemDto>> =
        ApiResponse.of(parents.schedule(childId))

    @POST @Path("/parent/devices")
    fun registerDevice(@Valid body: DeviceBody): ApiResponse<Map<String, Boolean>> {
        parents.registerDevice(body)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @DELETE @Path("/parent/devices")
    fun unregisterDevice(@QueryParam("token") token: String): ApiResponse<Map<String, Boolean>> {
        parents.unregisterDevice(token)
        return ApiResponse.of(mapOf("ok" to true))
    }

    /* ------------------------- Farzand bo'yicha kesimlar -------------------- */

    @GET @Path("/students/{childId}/subjects")
    fun subjects(@PathParam("childId") childId: UUID): ApiResponse<List<SubjectScoreDto>> =
        ApiResponse.of(parents.subjectScores(childId))

    @GET @Path("/students/{childId}/subjects/{subjectId}")
    fun subjectDetail(
        @PathParam("childId") childId: UUID,
        @PathParam("subjectId") subjectId: UUID,
    ): ApiResponse<SubjectDetailDto> = ApiResponse.of(parents.subjectDetail(childId, subjectId))

    @GET @Path("/students/{childId}/progress")
    fun progress(
        @PathParam("childId") childId: UUID,
        @QueryParam("period") period: String?,
    ): ApiResponse<ProgressTrendDto> = ApiResponse.of(parents.progress(childId, period ?: "MONTH"))

    @GET @Path("/students/{childId}/character")
    fun character(@PathParam("childId") childId: UUID): ApiResponse<List<CharacterGroupDto>> =
        ApiResponse.of(parents.character(childId))

    @GET @Path("/students/{childId}/strengths")
    fun strengths(@PathParam("childId") childId: UUID): ApiResponse<StrengthWeaknessDto> =
        ApiResponse.of(parents.strengths(childId))

    @GET @Path("/students/{childId}/interests")
    fun interests(@PathParam("childId") childId: UUID): ApiResponse<List<InterestDto>> =
        ApiResponse.of(parents.interests(childId))

    @GET @Path("/students/{childId}/abilities")
    fun abilities(@PathParam("childId") childId: UUID): ApiResponse<List<AbilityDto>> =
        ApiResponse.of(parents.abilities(childId))

    @GET @Path("/students/{childId}/recommendations")
    fun recommendations(@PathParam("childId") childId: UUID): ApiResponse<List<CognitiveTaskDto>> =
        ApiResponse.of(parents.recommendations(childId))

    @PATCH @Path("/recommendations/{id}")
    fun updateRecommendation(
        @PathParam("id") id: UUID,
        body: RecommendationStatusBody,
    ): ApiResponse<CognitiveTaskDto> = ApiResponse.of(parents.updateRecommendation(id, body.status))

    /* ------------------------------ Uy vazifasi ----------------------------- */

    @GET @Path("/homework/{id}")
    fun homeworkDetail(@PathParam("id") id: UUID): ApiResponse<HomeworkDto> =
        ApiResponse.of(parents.homeworkDetail(id))

    @PATCH @Path("/homework/{id}/status")
    fun markHomework(
        @PathParam("id") id: UUID,
        @QueryParam("childId") childId: UUID,
        body: HomeworkStatusBody,
    ): ApiResponse<HomeworkDto> = ApiResponse.of(parents.markHomework(id, childId, body.status))

    @GET @Path("/lessons/{id}")
    fun lesson(@PathParam("id") id: UUID): ApiResponse<LessonSummaryDto> = ApiResponse.of(parents.lesson(id))

    /* -------------------------------- Xabarlar ------------------------------ */

    @GET @Path("/messages")
    fun messages(@QueryParam("limit") limit: Int?): ApiResponse<List<MessageDto>> =
        ApiResponse.of(parents.messages((limit ?: 50).coerceIn(1, 200)))

    @POST @Path("/messages/{id}/read")
    fun markRead(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        parents.markMessageRead(id)
        return ApiResponse.of(mapOf("ok" to true))
    }
}

/** Ota-ona uchun AI: vazifani soddalashtirish / tushuntirish / yechib berish. */
@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PARENT)
class ParentAiResource {

    @Inject lateinit var parentAi: ParentAiService

    @POST @Path("/homework/{id}/ai")
    fun assist(@PathParam("id") id: UUID, @Valid body: AiHomeworkBody): ApiResponse<AiHomeworkResultDto> =
        ApiResponse.of(parentAi.assist(id, body.mode))

    /** docs/03-api-contract.md dagi alohida yo'llar — bir xil oqim. */
    @POST @Path("/homework/{id}/simplify")
    fun simplify(@PathParam("id") id: UUID): ApiResponse<AiHomeworkResultDto> =
        ApiResponse.of(parentAi.assist(id, "SIMPLIFY"))

    @POST @Path("/homework/{id}/explain")
    fun explain(@PathParam("id") id: UUID): ApiResponse<AiHomeworkResultDto> =
        ApiResponse.of(parentAi.assist(id, "EXPLAIN"))

    @POST @Path("/homework/{id}/solve")
    fun solve(@PathParam("id") id: UUID): ApiResponse<AiHomeworkResultDto> =
        ApiResponse.of(parentAi.assist(id, "SOLVE"))

    @POST @Path("/ai/messages")
    fun chat(@Valid body: AiChatBody): ApiResponse<AiMessageDto> = ApiResponse.of(parentAi.chat(body))

    @GET @Path("/ai/messages")
    fun history(@QueryParam("limit") limit: Int?): ApiResponse<List<AiMessageDto>> =
        ApiResponse.of(parentAi.history((limit ?: 30).coerceIn(1, 100)))
}
