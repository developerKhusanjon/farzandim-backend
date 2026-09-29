package uz.farzandim.admin.api

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
import jakarta.ws.rs.core.Response
import uz.farzandim.admin.dto.AiSummaryDto
import uz.farzandim.admin.dto.AiUsageRowDto
import uz.farzandim.admin.dto.AlertDto
import uz.farzandim.admin.dto.AnnouncementBody
import uz.farzandim.admin.dto.AnnouncementResultDto
import uz.farzandim.admin.dto.AuditRowDto
import uz.farzandim.admin.dto.BillingSummaryDto
import uz.farzandim.admin.dto.CancelSubscriptionBody
import uz.farzandim.admin.dto.CreateAdminBody
import uz.farzandim.admin.dto.CreateNoteBody
import uz.farzandim.admin.dto.CreateSchoolBody
import uz.farzandim.admin.dto.CreateSchoolResultDto
import uz.farzandim.admin.dto.CreateSubscriptionBody
import uz.farzandim.admin.dto.CreateTeamMemberBody
import uz.farzandim.admin.dto.CredentialsDto
import uz.farzandim.admin.dto.DeliveryStatusDto
import uz.farzandim.admin.dto.EmailRowDto
import uz.farzandim.admin.dto.FeatureFlagDto
import uz.farzandim.admin.dto.GroupRowDto
import uz.farzandim.admin.dto.JobRowDto
import uz.farzandim.admin.dto.MarkPaidBody
import uz.farzandim.admin.dto.PlanDto
import uz.farzandim.admin.dto.PlatformStatsDto
import uz.farzandim.admin.dto.PlatformUserDto
import uz.farzandim.admin.dto.SchoolDetailDto
import uz.farzandim.admin.dto.SchoolMetricsDto
import uz.farzandim.admin.dto.SchoolNoteDto
import uz.farzandim.admin.dto.SchoolOverviewDto
import uz.farzandim.admin.dto.SchoolRowDto
import uz.farzandim.admin.dto.SetAdminLevelBody
import uz.farzandim.admin.dto.SmsRowDto
import uz.farzandim.admin.dto.StatusChangeBody
import uz.farzandim.admin.dto.StudentRowDto
import uz.farzandim.admin.dto.SubscriptionDto
import uz.farzandim.admin.dto.TeacherRowDto
import uz.farzandim.admin.dto.TestEmailBody
import uz.farzandim.admin.dto.TestSmsBody
import uz.farzandim.admin.dto.TrendPointDto
import uz.farzandim.admin.dto.UpdateFlagBody
import uz.farzandim.admin.dto.UpdatePlanBody
import uz.farzandim.admin.dto.UpdateSchoolBody
import uz.farzandim.admin.service.BillingService
import uz.farzandim.admin.service.InsightService
import uz.farzandim.admin.service.PlatformService
import uz.farzandim.admin.service.SchoolAdminService
import uz.farzandim.admin.service.UserAdminService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.JobStatus
import uz.farzandim.shared.domain.PaymentStatus
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.SchoolStatus
import uz.farzandim.shared.domain.SubscriptionPlanCode
import uz.farzandim.shared.domain.SubscriptionStatus
import uz.farzandim.shared.domain.TokenAudience
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.AuthResponse
import uz.farzandim.shared.security.AuthService
import uz.farzandim.shared.security.AuthUserDto
import uz.farzandim.shared.security.ChangePasswordBody
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.LoginRequest
import uz.farzandim.shared.security.RefreshRequest
import uz.farzandim.shared.security.Roles
import java.time.LocalDate
import java.util.UUID

@ApplicationPath("/api/v1")
class AdminApplication : Application()

/**
 * Super admin autentifikatsiyasi.
 *
 * Faqat SUPER_ADMIN roli. Maktab admini yoki o'qituvchi bu yerga
 * kira olmaydi — token `aud` da `farzandim-admin` talab qilinadi
 * va rol ro'yxati bitta qiymatdan iborat.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AdminAuthResource {

    @Inject lateinit var auth: AuthService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var currentUser: CurrentUser

    @POST @Path("/login") @PermitAll
    fun login(@Valid body: LoginRequest, @Context headers: HttpHeaders): ApiResponse<AuthResponse> =
        ApiResponse.of(auth.login(body, TokenAudience.ADMIN, setOf(Role.SUPER_ADMIN), clientIp(headers)))

    @POST @Path("/refresh") @PermitAll
    fun refresh(@Valid body: RefreshRequest, @Context headers: HttpHeaders): ApiResponse<AuthResponse> =
        ApiResponse.of(auth.refresh(body.refreshToken, TokenAudience.ADMIN, clientIp(headers)))

    @POST @Path("/logout") @PermitAll
    fun logout(body: RefreshRequest?): ApiResponse<Map<String, Boolean>> {
        auth.logout(body?.refreshToken)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @GET @Path("/me") @RolesAllowed(Roles.SUPER_ADMIN)
    fun me(): ApiResponse<Map<String, Any?>> {
        val user = users.findById(currentUser.userId) ?: throw ApiException.unauthorized()
        return ApiResponse.of(mapOf("user" to AuthUserDto.from(user)))
    }

    @POST @Path("/password/change") @RolesAllowed(Roles.SUPER_ADMIN)
    fun changePassword(@Valid body: ChangePasswordBody): ApiResponse<Map<String, Boolean>> {
        auth.changePassword(currentUser.userId, body)
        return ApiResponse.of(mapOf("ok" to true))
    }

    private fun clientIp(headers: HttpHeaders): String? =
        headers.getHeaderString("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()
            ?: headers.getHeaderString("X-Real-IP")
}

/* ================================ Maktablar ================================ */

/**
 * Maktablar: ro'yxat, shartnoma bo'yicha ulash, kartochka, holat,
 * adminlar va ichki ma'lumot (faqat o'qish).
 */
@Path("/admin/schools")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SUPER_ADMIN)
class SchoolsResource {

    @Inject lateinit var schools: SchoolAdminService
    @Inject lateinit var insight: InsightService
    @Inject lateinit var billing: BillingService

    @GET
    fun list(
        @QueryParam("q") q: String?,
        @QueryParam("status") status: SchoolStatus?,
        @QueryParam("plan") plan: SubscriptionPlanCode?,
        @QueryParam("region") region: String?,
        /** Obunasi shu kun ichida tugaydiganlar (tugaganlar ham kiradi). */
        @QueryParam("expiringDays") expiringDays: Int?,
        @QueryParam("unpaid") unpaid: Boolean?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        @QueryParam("sort") sort: String?,
    ): ApiResponse<List<SchoolRowDto>> =
        ApiResponse.paged(
            schools.listSchools(
                SchoolAdminService.SchoolFilter(q, status, plan, region, expiringDays, unpaid == true),
                PageRequest.of(page, size, sort),
            ),
        )

    /** Jadvalni Excel'da ochish uchun — UTF-8 BOM bilan CSV. */
    @GET @Path("/export") @Produces("text/csv")
    fun export(
        @QueryParam("q") q: String?,
        @QueryParam("status") status: SchoolStatus?,
        @QueryParam("plan") plan: SubscriptionPlanCode?,
    ): Response =
        Response.ok(schools.exportCsv(SchoolAdminService.SchoolFilter(q, status, plan)))
            .header("Content-Disposition", "attachment; filename=\"maktablar-${LocalDate.now()}.csv\"")
            .type("text/csv; charset=utf-8")
            .build()

    /** Maktab + adminlar + birinchi obuna. Parollar javobda bir marta qaytadi. */
    @POST
    fun create(@Valid body: CreateSchoolBody): ApiResponse<CreateSchoolResultDto> =
        ApiResponse.of(schools.createSchool(body))

    @GET @Path("/{id}")
    fun get(@PathParam("id") id: UUID): ApiResponse<SchoolDetailDto> = ApiResponse.of(schools.getSchool(id))

    @PATCH @Path("/{id}")
    fun update(@PathParam("id") id: UUID, @Valid body: UpdateSchoolBody): ApiResponse<SchoolDetailDto> =
        ApiResponse.of(schools.updateSchool(id, body))

    @POST @Path("/{id}/suspend")
    fun suspend(@PathParam("id") id: UUID, body: StatusChangeBody?): ApiResponse<SchoolDetailDto> =
        ApiResponse.of(schools.setSchoolStatus(id, SchoolStatus.SUSPENDED, body?.reason))

    @POST @Path("/{id}/activate")
    fun activate(@PathParam("id") id: UUID, body: StatusChangeBody?): ApiResponse<SchoolDetailDto> =
        ApiResponse.of(schools.setSchoolStatus(id, SchoolStatus.ACTIVE, body?.reason))

    @POST @Path("/{id}/archive")
    fun archive(@PathParam("id") id: UUID, body: StatusChangeBody?): ApiResponse<SchoolDetailDto> =
        ApiResponse.of(schools.setSchoolStatus(id, SchoolStatus.ARCHIVED, body?.reason))

    /* ------------------------------ Adminlar ------------------------------ */

    @GET @Path("/{id}/admins")
    fun admins(@PathParam("id") id: UUID): ApiResponse<List<PlatformUserDto>> =
        ApiResponse.of(schools.listAdmins(id))

    @POST @Path("/{id}/admins")
    fun createAdmin(@PathParam("id") id: UUID, @Valid body: CreateAdminBody): ApiResponse<CredentialsDto> =
        ApiResponse.of(schools.createAdminAccount(id, body))

    /* ---------------------- Ichki ma'lumot (faqat o'qish) ------------------ */

    @GET @Path("/{id}/overview")
    fun overview(@PathParam("id") id: UUID): ApiResponse<SchoolOverviewDto> =
        ApiResponse.of(insight.overview(id))

    @GET @Path("/{id}/students")
    fun students(
        @PathParam("id") id: UUID,
        @QueryParam("q") q: String?,
        @QueryParam("groupId") groupId: UUID?,
        @QueryParam("status") status: String?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ): ApiResponse<List<StudentRowDto>> =
        ApiResponse.paged(insight.students(id, q, groupId, status, PageRequest.of(page, size, null)))

    @GET @Path("/{id}/teachers")
    fun teachers(@PathParam("id") id: UUID): ApiResponse<List<TeacherRowDto>> =
        ApiResponse.of(insight.teachers(id))

    @GET @Path("/{id}/groups")
    fun groups(@PathParam("id") id: UUID): ApiResponse<List<GroupRowDto>> =
        ApiResponse.of(insight.groups(id))

    /** Maktabning barcha hisoblari: adminlar, o'qituvchilar, ota-onalar. */
    @GET @Path("/{id}/users")
    fun users(
        @PathParam("id") id: UUID,
        @QueryParam("role") role: Role?,
        @QueryParam("q") q: String?,
        @QueryParam("status") status: UserStatus?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ): ApiResponse<List<PlatformUserDto>> =
        ApiResponse.paged(insight.schoolUsers(id, role, q, status, PageRequest.of(page, size, null)))

    /* ------------------------------ Qaydlar ------------------------------ */

    @GET @Path("/{id}/notes")
    fun notes(@PathParam("id") id: UUID): ApiResponse<List<SchoolNoteDto>> = ApiResponse.of(schools.listNotes(id))

    @POST @Path("/{id}/notes")
    fun addNote(@PathParam("id") id: UUID, @Valid body: CreateNoteBody): ApiResponse<SchoolNoteDto> =
        ApiResponse.of(schools.addNote(id, body))

    /* ------------------------------- Obuna ------------------------------- */

    @GET @Path("/{id}/subscriptions")
    fun subscriptions(@PathParam("id") id: UUID): ApiResponse<List<SubscriptionDto>> =
        ApiResponse.of(billing.listForSchool(id))

    @POST @Path("/{id}/subscriptions")
    fun createSubscription(
        @PathParam("id") id: UUID,
        @Valid body: CreateSubscriptionBody,
    ): ApiResponse<SubscriptionDto> = ApiResponse.of(billing.createSubscription(id, body))
}

@Path("/admin/notes")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SUPER_ADMIN)
class NotesResource {

    @Inject lateinit var schools: SchoolAdminService

    @DELETE @Path("/{id}")
    fun delete(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        schools.deleteNote(id)
        return ApiResponse.of(mapOf("ok" to true))
    }
}

/* ============================= Obuna va tariflar ============================ */

@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SUPER_ADMIN)
class BillingResource {

    @Inject lateinit var billing: BillingService

    @GET @Path("/subscriptions")
    fun subscriptions(
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("paymentStatus") paymentStatus: PaymentStatus?,
        @QueryParam("status") status: SubscriptionStatus?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ): ApiResponse<List<SubscriptionDto>> =
        ApiResponse.paged(billing.list(schoolId, paymentStatus, status, PageRequest.of(page, size, null)))

    @GET @Path("/subscriptions/summary")
    fun summary(@QueryParam("months") months: Int?): ApiResponse<BillingSummaryDto> =
        ApiResponse.of(billing.summary((months ?: 12).coerceIn(1, 36)))

    @POST @Path("/subscriptions/{id}/pay")
    fun pay(@PathParam("id") id: UUID, body: MarkPaidBody?): ApiResponse<SubscriptionDto> =
        ApiResponse.of(billing.markPaid(id, body ?: MarkPaidBody()))

    @POST @Path("/subscriptions/{id}/cancel")
    fun cancel(@PathParam("id") id: UUID, body: CancelSubscriptionBody?): ApiResponse<SubscriptionDto> =
        ApiResponse.of(billing.cancel(id, body?.reason))

    @GET @Path("/plans")
    fun plans(): ApiResponse<List<PlanDto>> = ApiResponse.of(billing.listPlans())

    @PATCH @Path("/plans/{code}")
    fun updatePlan(@PathParam("code") code: String, @Valid body: UpdatePlanBody): ApiResponse<PlanDto> =
        ApiResponse.of(billing.updatePlan(code.uppercase(), body))
}

/* ========================== Foydalanuvchilar va jamoa ========================= */

@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SUPER_ADMIN)
class UsersResource {

    @Inject lateinit var users: UserAdminService

    /** Butun platforma bo'yicha qidiruv: ism, login, telefon, email. */
    @GET @Path("/users")
    fun search(
        @QueryParam("q") q: String?,
        @QueryParam("role") role: Role?,
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("status") status: UserStatus?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
    ): ApiResponse<List<PlatformUserDto>> =
        ApiResponse.paged(users.search(q, role, schoolId, status, PageRequest.of(page, size, null)))

    @GET @Path("/users/{id}")
    fun get(@PathParam("id") id: UUID): ApiResponse<PlatformUserDto> = ApiResponse.of(users.get(id))

    @POST @Path("/users/{id}/block")
    fun block(@PathParam("id") id: UUID): ApiResponse<PlatformUserDto> =
        ApiResponse.of(users.setStatus(id, UserStatus.BLOCKED))

    @POST @Path("/users/{id}/unblock")
    fun unblock(@PathParam("id") id: UUID): ApiResponse<PlatformUserDto> =
        ApiResponse.of(users.setStatus(id, UserStatus.ACTIVE))

    @POST @Path("/users/{id}/reset-password")
    fun resetPassword(@PathParam("id") id: UUID): ApiResponse<CredentialsDto> =
        ApiResponse.of(users.resetPassword(id))

    /** Barcha qurilmalardan chiqarib yuborish (parol o'zgarmaydi). */
    @POST @Path("/users/{id}/revoke-sessions")
    fun revokeSessions(@PathParam("id") id: UUID): ApiResponse<Map<String, Int>> =
        ApiResponse.of(mapOf("revoked" to users.revokeSessions(id)))

    /** Maktab admini darajasi: SUPER ↔ REGULAR. */
    @PATCH @Path("/users/{id}/admin-level")
    fun adminLevel(@PathParam("id") id: UUID, body: SetAdminLevelBody): ApiResponse<PlatformUserDto> =
        ApiResponse.of(users.setAdminLevel(id, body.level))

    /** Platforma jamoasi — boshqa super adminlar. */
    @GET @Path("/team")
    fun team(): ApiResponse<List<PlatformUserDto>> = ApiResponse.of(users.team())

    @POST @Path("/team")
    fun addTeamMember(@Valid body: CreateTeamMemberBody): ApiResponse<CredentialsDto> =
        ApiResponse.of(users.createTeamMember(body))
}

/* ================================ Statistika ================================ */

@Path("/admin/stats")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SUPER_ADMIN)
class StatsResource {

    @Inject lateinit var insight: InsightService

    @GET
    fun stats(): ApiResponse<PlatformStatsDto> = ApiResponse.of(insight.platformStats())

    @GET @Path("/trends")
    fun trends(@QueryParam("months") months: Int?): ApiResponse<List<TrendPointDto>> =
        ApiResponse.of(insight.trends((months ?: 12).coerceIn(1, 36)))

    /** "Diqqat talab qiladi" ro'yxati — bosh sahifa uchun. */
    @GET @Path("/alerts")
    fun alerts(): ApiResponse<List<AlertDto>> = ApiResponse.of(insight.alerts())

    /** Maktablarni taqqoslash jadvali (30 kunlik ko'rsatkichlar). */
    @GET @Path("/schools")
    fun schools(): ApiResponse<List<SchoolMetricsDto>> = ApiResponse.of(insight.schoolMetrics())
}

/* ========================= Monitoring, bayroq, audit ========================= */

@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SUPER_ADMIN)
class PlatformResource {

    @Inject lateinit var platform: PlatformService

    /* ----------------------------------- AI --------------------------------- */

    @GET @Path("/ai/usage")
    fun aiUsage(
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("days") days: Int?,
    ): ApiResponse<List<AiUsageRowDto>> =
        ApiResponse.of(platform.aiUsage(schoolId, (days ?: 30).coerceIn(1, 365)))

    @GET @Path("/ai/summary")
    fun aiSummary(
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("days") days: Int?,
    ): ApiResponse<AiSummaryDto> =
        ApiResponse.of(platform.aiSummary(schoolId, (days ?: 30).coerceIn(1, 365)))

    /* --------------------------------- Joblar ------------------------------- */

    @GET @Path("/jobs")
    fun jobs(
        @QueryParam("status") status: JobStatus?,
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("limit") limit: Int?,
    ): ApiResponse<List<JobRowDto>> =
        ApiResponse.of(platform.listJobs(status, schoolId, (limit ?: 100).coerceIn(1, 500)))

    @POST @Path("/jobs/{id}/retry")
    fun retryJob(@PathParam("id") id: UUID): ApiResponse<JobRowDto> = ApiResponse.of(platform.retryJob(id))

    /* --------------------------- SMS, email va push ------------------------- */

    /** Qaysi provayder faol, bugun qancha SMS ketdi, nechta qurilma ulangan. */
    @GET @Path("/delivery/status")
    fun deliveryStatus(): ApiResponse<DeliveryStatusDto> = ApiResponse.of(platform.deliveryStatus())

    @GET @Path("/sms")
    fun sms(
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("limit") limit: Int?,
    ): ApiResponse<List<SmsRowDto>> =
        ApiResponse.of(platform.listSms(schoolId, (limit ?: 100).coerceIn(1, 500)))

    /** Shlyuzni tekshirish — haqiqiy SMS ketadi va hisobga tushadi. */
    @POST @Path("/sms/test")
    fun testSms(@Valid body: TestSmsBody): ApiResponse<Map<String, Any?>> {
        val result = platform.sendTestSms(body.phone, body.text)
        return ApiResponse.of(
            mapOf(
                "accepted" to result.accepted,
                "providerMessageId" to result.providerMessageId,
                "errorCode" to result.errorCode,
                "errorMessage" to result.errorMessage,
            ),
        )
    }

    @GET @Path("/email")
    fun emails(
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("limit") limit: Int?,
    ): ApiResponse<List<EmailRowDto>> =
        ApiResponse.of(platform.listEmails(schoolId, (limit ?: 100).coerceIn(1, 500)))

    /** SMTP ulanishini tekshirish — haqiqiy xat ketadi. */
    @POST @Path("/email/test")
    fun testEmail(@Valid body: TestEmailBody): ApiResponse<Map<String, Any?>> {
        val result = platform.sendTestEmail(body.email, body.subject, body.text)
        return ApiResponse.of(
            mapOf(
                "accepted" to result.accepted,
                "errorCode" to result.errorCode,
                "errorMessage" to result.errorMessage,
            ),
        )
    }

    /** Push'ni tekshirish — foydalanuvchining barcha faol qurilmalariga. */
    @POST @Path("/push/test")
    fun testPush(@QueryParam("userId") userId: UUID): ApiResponse<Map<String, Any>> =
        ApiResponse.of(platform.sendTestPush(userId))

    /* ------------------------------ Bayroqlar ------------------------------ */

    @GET @Path("/feature-flags")
    fun flags(): ApiResponse<List<FeatureFlagDto>> = ApiResponse.of(platform.listFlags())

    @PATCH @Path("/feature-flags/{key}")
    fun updateFlag(@PathParam("key") key: String, body: UpdateFlagBody): ApiResponse<FeatureFlagDto> =
        ApiResponse.of(platform.updateFlag(key, body))

    /* ------------------------------- E'lonlar ------------------------------ */

    /** Maktab adminlari (va xohlasa o'qituvchilar) CRM'da bildirishnoma sifatida ko'radi. */
    @POST @Path("/announcements")
    fun announce(@Valid body: AnnouncementBody): ApiResponse<AnnouncementResultDto> =
        ApiResponse.of(platform.announce(body))

    /* -------------------------------- Audit -------------------------------- */

    @GET @Path("/audit")
    fun audit(
        @QueryParam("schoolId") schoolId: UUID?,
        @QueryParam("action") action: String?,
        @QueryParam("actorId") actorId: UUID?,
        @QueryParam("entityType") entityType: String?,
        @QueryParam("from") from: LocalDate?,
        @QueryParam("to") to: LocalDate?,
        @QueryParam("page") page: Int?,
        @QueryParam("size") size: Int?,
        /** Eski parametr — `size` bilan bir xil. */
        @QueryParam("limit") limit: Int?,
    ): ApiResponse<List<AuditRowDto>> =
        ApiResponse.paged(
            platform.listAudit(
                PlatformService.AuditFilter(schoolId, action, actorId, entityType, from, to),
                PageRequest.of(page, size ?: limit ?: 50, null),
            ),
        )

    /** Filtr uchun: jurnalda uchragan amallar ro'yxati. */
    @GET @Path("/audit/actions")
    fun auditActions(): ApiResponse<List<String>> = ApiResponse.of(platform.auditActions())
}
