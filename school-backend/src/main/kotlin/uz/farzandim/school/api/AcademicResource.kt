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
import uz.farzandim.school.dto.AttendanceBody
import uz.farzandim.school.dto.AttendanceDto
import uz.farzandim.school.dto.BulkAttendanceBody
import uz.farzandim.school.dto.BulkGradesBody
import uz.farzandim.school.dto.GradeBody
import uz.farzandim.school.dto.GradeDto
import uz.farzandim.school.dto.GradebookDto
import uz.farzandim.school.dto.LessonDto
import uz.farzandim.school.dto.LessonSummaryBody
import uz.farzandim.school.dto.LessonSummaryDraftDto
import uz.farzandim.school.dto.LessonUpdateBody
import uz.farzandim.school.dto.ScheduleSlotBody
import uz.farzandim.school.dto.ScheduleSlotDto
import uz.farzandim.school.service.AiFeatureService
import uz.farzandim.school.service.JournalService
import uz.farzandim.school.service.ScheduleService
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import java.time.LocalDate
import java.util.UUID

@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class AcademicResource {

    @Inject lateinit var schedule: ScheduleService
    @Inject lateinit var journal: JournalService
    @Inject lateinit var aiFeatures: AiFeatureService
    @Inject lateinit var currentUser: CurrentUser

    private val schoolId: UUID get() = currentUser.requireSchoolId()

    /* --------------------------------- Jadval ------------------------------- */

    @GET @Path("/schedule")
    fun schedule(
        @QueryParam("groupId") groupId: UUID?,
        @QueryParam("teacherId") teacherId: UUID?,
    ): ApiResponse<List<ScheduleSlotDto>> = ApiResponse.of(schedule.listSlots(schoolId, groupId, teacherId))

    @GET @Path("/groups/{id}/schedule")
    fun groupSchedule(@PathParam("id") id: UUID): ApiResponse<List<ScheduleSlotDto>> =
        ApiResponse.of(schedule.listSlots(schoolId, id, null))

    @POST @Path("/schedule/slots")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun createSlot(@Valid body: ScheduleSlotBody): ApiResponse<ScheduleSlotDto> =
        ApiResponse.of(schedule.createSlot(schoolId, body))

    @PATCH @Path("/schedule/slots/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun updateSlot(@PathParam("id") id: UUID, @Valid body: ScheduleSlotBody): ApiResponse<ScheduleSlotDto> =
        ApiResponse.of(schedule.updateSlot(schoolId, id, body))

    @DELETE @Path("/schedule/slots/{id}")
    @RolesAllowed(Roles.SCHOOL_ADMIN, Roles.SUPER_ADMIN)
    fun deleteSlot(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        schedule.deleteSlot(schoolId, id)
        return ApiResponse.of(mapOf("ok" to true))
    }

    /* --------------------------------- Darslar ------------------------------ */

    @GET @Path("/lessons")
    fun lessons(
        @QueryParam("date") date: String?,
        @QueryParam("groupId") groupId: UUID?,
        @QueryParam("teacherId") teacherId: UUID?,
    ): ApiResponse<List<LessonDto>> =
        ApiResponse.of(schedule.listLessons(schoolId, date?.let(LocalDate::parse), groupId, teacherId))

    @GET @Path("/lessons/today")
    fun today(): ApiResponse<List<LessonDto>> {
        val teacherId = if (currentUser.role == uz.farzandim.shared.domain.Role.TEACHER) null else null
        return ApiResponse.of(schedule.listLessons(schoolId, LocalDate.now(), null, teacherId))
    }

    @GET @Path("/lessons/{id}")
    fun lesson(@PathParam("id") id: UUID): ApiResponse<LessonDto> = ApiResponse.of(schedule.getLesson(schoolId, id))

    @PATCH @Path("/lessons/{id}")
    fun updateLesson(@PathParam("id") id: UUID, body: LessonUpdateBody): ApiResponse<LessonDto> =
        ApiResponse.of(schedule.updateLesson(schoolId, id, body))

    @POST @Path("/lessons/{id}/start")
    fun start(@PathParam("id") id: UUID): ApiResponse<LessonDto> = ApiResponse.of(schedule.startLesson(schoolId, id))

    @POST @Path("/lessons/{id}/finish")
    fun finish(@PathParam("id") id: UUID): ApiResponse<LessonDto> = ApiResponse.of(schedule.finishLesson(schoolId, id))

    /* ----------------------------- Dars xulosasi (AI) ----------------------- */

    @POST @Path("/lessons/{id}/summary/generate")
    fun generateSummary(@PathParam("id") id: UUID): ApiResponse<LessonSummaryDraftDto> =
        ApiResponse.of(aiFeatures.generateLessonSummary(schoolId, id))

    @PATCH @Path("/lessons/{id}/summary")
    fun updateSummary(@PathParam("id") id: UUID, body: LessonSummaryBody): ApiResponse<LessonSummaryDraftDto> =
        ApiResponse.of(aiFeatures.updateLessonSummary(schoolId, id, body))

    @POST @Path("/lessons/{id}/summary/send")
    fun sendSummary(@PathParam("id") id: UUID): ApiResponse<LessonSummaryDraftDto> =
        ApiResponse.of(aiFeatures.sendLessonSummary(schoolId, id))

    /* ---------------------------------- Baho -------------------------------- */

    @POST @Path("/grades")
    fun createGrade(@Valid body: GradeBody): ApiResponse<GradeDto> =
        ApiResponse.of(journal.createGrade(schoolId, body))

    @POST @Path("/grades/bulk")
    fun createGrades(body: BulkGradesBody): ApiResponse<List<GradeDto>> =
        ApiResponse.of(journal.createGradesBulk(schoolId, body.grades))

    @DELETE @Path("/grades/{id}")
    fun deleteGrade(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        journal.deleteGrade(schoolId, id)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @GET @Path("/gradebook")
    fun gradebook(
        @QueryParam("groupId") groupId: UUID,
        @QueryParam("subjectId") subjectId: UUID,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ): ApiResponse<GradebookDto> = ApiResponse.of(
        journal.gradebook(
            schoolId, groupId, subjectId,
            from?.let(LocalDate::parse) ?: LocalDate.now().minusDays(30),
            to?.let(LocalDate::parse) ?: LocalDate.now(),
        ),
    )

    /* -------------------------------- Davomat ------------------------------- */

    @GET @Path("/attendance")
    fun attendance(@QueryParam("lessonId") lessonId: UUID): ApiResponse<List<AttendanceDto>> =
        ApiResponse.of(journal.listAttendance(schoolId, lessonId))

    @POST @Path("/attendance")
    fun markAttendance(@Valid body: AttendanceBody): ApiResponse<List<AttendanceDto>> =
        ApiResponse.of(journal.markAttendanceBulk(schoolId, listOf(body)))

    @POST @Path("/attendance/bulk")
    fun markAttendanceBulk(body: BulkAttendanceBody): ApiResponse<List<AttendanceDto>> =
        ApiResponse.of(journal.markAttendanceBulk(schoolId, body.records))

    @GET @Path("/attendance/journal")
    fun attendanceJournal(
        @QueryParam("groupId") groupId: UUID,
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
    ): ApiResponse<Map<String, Any>> = ApiResponse.of(
        journal.attendanceJournal(
            schoolId, groupId,
            from?.let(LocalDate::parse) ?: LocalDate.now().withDayOfMonth(1),
            to?.let(LocalDate::parse) ?: LocalDate.now(),
        ),
    )
}
