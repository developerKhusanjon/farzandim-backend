package uz.farzandim.school.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uz.farzandim.shared.analytics.SubjectScoreDto
import uz.farzandim.shared.analytics.TopicScoreDto
import uz.farzandim.shared.analytics.TrendPoint
import uz.farzandim.shared.domain.AssessmentTaskStatus
import uz.farzandim.shared.domain.AttendanceStatus
import uz.farzandim.shared.domain.CognitiveArea
import uz.farzandim.shared.domain.Difficulty
import uz.farzandim.shared.domain.GradeType
import uz.farzandim.shared.domain.GradingSystem
import uz.farzandim.shared.domain.HomeworkScope
import uz.farzandim.shared.domain.LessonStatus
import uz.farzandim.shared.domain.Locale
import uz.farzandim.shared.domain.MessageCategory
import uz.farzandim.shared.domain.ParentRelation
import uz.farzandim.shared.domain.PerformancePeriod
import uz.farzandim.shared.domain.RecommendationStatus
import uz.farzandim.shared.domain.SchoolAdminLevel
import uz.farzandim.shared.domain.SkillCategory
import uz.farzandim.shared.domain.StudentStatus
import uz.farzandim.shared.domain.SubmissionStatus
import uz.farzandim.shared.domain.SummaryStatus
import uz.farzandim.shared.domain.UserStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Javob DTO'lari — farzandim-web/src/entities/types.ts bilan bir xil.
 * Maydon nomi yoki turi o'zgarsa, ikkalasi birga o'zgaradi.
 */

/* ---------------------------------- Maktab --------------------------------- */

data class SchoolSettingsDto(
    val notifyParentsOnGrade: Boolean = true,
    val notifyParentsOnHomework: Boolean = true,
    val notifyParentsOnAbsence: Boolean = true,
    val aiAssistantEnabled: Boolean = true,
    val autoLessonSummary: Boolean = false,
    val weeklyReportDay: Int = 6,
    val lessonDurationMinutes: Int = 45,
    val breakMinutes: Int = 10,
    val dayStart: String = "08:30",
)

data class SchoolDto(
    val id: UUID,
    val name: String,
    val shortName: String,
    val logoUrl: String?,
    val address: String,
    val phone: String,
    val email: String,
    val academicYear: String,
    val gradingSystem: GradingSystem,
    val locale: Locale,
    val studentCount: Long,
    val teacherCount: Long,
    val groupCount: Long,
    val subscriptionPlan: String,
    val subscriptionUntil: LocalDate?,
    val settings: SchoolSettingsDto,
    /** Tarif imkoniyatlari — frontend mavjud bo'lmagan AI tugmalarini yashiradi. */
    val planFeatures: Map<String, Boolean> = emptyMap(),
    val aiDailyLimit: Int = 0,
)

data class StatValue(val value: Long, val delta: Long)

data class SchoolStatsDto(
    val students: StatValue,
    val teachers: StatValue,
    val groups: StatValue,
    val attendanceToday: StatValue,
    val needsAttention: StatValue,
)

data class UpdateSchoolBody(
    val name: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val academicYear: String? = null,
    val gradingSystem: GradingSystem? = null,
    val locale: Locale? = null,
    val settings: SchoolSettingsDto? = null,
)

/* ----------------------------- Fan va mavzular ------------------------------ */

data class SubjectDto(
    val id: UUID,
    val schoolId: UUID,
    val name: String,
    val shortName: String,
    val colorKey: String,
    val iconKey: String,
    val hoursPerWeek: Int,
    val gradeLevels: List<Int>,
)

data class SubjectBody(
    @field:NotBlank val name: String = "",
    @field:NotBlank val shortName: String = "",
    val colorKey: String = "blue",
    val iconKey: String = "book",
    @field:Min(0) val hoursPerWeek: Int = 2,
    val gradeLevels: List<Int> = emptyList(),
)

data class TopicDto(
    val id: UUID,
    val subjectId: UUID,
    val gradeLevel: Int,
    val orderIndex: Int,
    val title: String,
    val description: String?,
    val expectedTerms: List<String>,
)

data class TopicBody(
    @field:NotBlank val title: String = "",
    val description: String? = null,
    @field:Min(1) val gradeLevel: Int = 1,
    val orderIndex: Int = 0,
    val expectedTerms: List<String> = emptyList(),
)

/* --------------------------------- Guruhlar -------------------------------- */

data class GroupDto(
    val id: UUID,
    val schoolId: UUID,
    val name: String,
    val gradeLevel: Int,
    val academicYear: String,
    val classTeacherId: UUID?,
    val classTeacherName: String,
    val room: String?,
    val studentCount: Long,
    val subjectIds: List<UUID>,
    val averageScore: Int,
    val attendanceRate: Int,
)

data class GroupBody(
    @field:NotBlank val name: String = "",
    @field:Min(1) val gradeLevel: Int = 1,
    val academicYear: String? = null,
    val classTeacherId: UUID? = null,
    val room: String? = null,
)

/* ------------------------------- O'qituvchilar ------------------------------ */

data class TeacherDto(
    val id: UUID,
    val userId: UUID,
    val schoolId: UUID,
    val firstName: String,
    val lastName: String,
    val middleName: String?,
    val avatarUrl: String?,
    val username: String?,
    val email: String?,
    val phone: String?,
    val employeeNo: String?,
    val subjectIds: List<UUID>,
    val groupIds: List<UUID>,
    val qualification: String?,
    val experienceYears: Int,
    val hiredAt: LocalDate?,
    val status: UserStatus,
    val averageGroupScore: Int,
)

data class TeacherBody(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    val middleName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val employeeNo: String? = null,
    val qualification: String? = null,
    val experienceYears: Int = 0,
    val hiredAt: LocalDate? = null,
    val bio: String? = null,
    /** true bo'lsa login/parol yaratiladi va bir marta javobda qaytariladi. */
    val createAccount: Boolean = true,
)

data class CredentialsDto(
    val username: String,
    val password: String,
    val note: String = "Parol faqat shu javobda ko'rsatiladi — saqlab qo'ying.",
)

/* ------------------------------ Maktab adminlari ----------------------------- */

data class SchoolAdminDto(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val username: String?,
    val phone: String?,
    val email: String?,
    /** SUPER — maktab superadmini, REGULAR — oddiy admin. */
    val level: SchoolAdminLevel,
    val status: UserStatus,
    val lastLoginAt: Instant?,
    val isMe: Boolean,
)

data class CreateSchoolAdminBody(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    val phone: String? = null,
    @field:Email val email: String? = null,
    val level: SchoolAdminLevel = SchoolAdminLevel.REGULAR,
)

data class AdminLevelBody(val level: SchoolAdminLevel = SchoolAdminLevel.REGULAR)

data class AssignmentBody(
    val groupId: UUID,
    val subjectId: UUID,
)

/* -------------------------------- O'quvchilar ------------------------------- */

data class StudentParentLinkDto(
    val parentId: UUID,
    val fullName: String,
    val relation: ParentRelation,
    val phone: String?,
    val isPrimary: Boolean,
    val hasAccount: Boolean,
)

data class StudentDto(
    val id: UUID,
    val schoolId: UUID,
    val firstName: String,
    val lastName: String,
    val middleName: String?,
    val avatarUrl: String?,
    val birthDate: LocalDate?,
    val gender: String?,
    val gradeLevel: Int,
    val groupId: UUID?,
    val groupName: String,
    val enrolledAt: LocalDate,
    val status: StudentStatus,
    val parents: List<StudentParentLinkDto>,
    val academicScore: Int,
    val skillScore: Int,
    val knowledgeScore: Int,
    val attendanceRate: Int,
    val scoreDelta: Int,
    val needsAttention: Boolean,
)

data class ParentInput(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    val phone: String? = null,
    val email: String? = null,
    val relation: ParentRelation = ParentRelation.MOTHER,
    val isPrimary: Boolean = true,
)

data class StudentBody(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    val middleName: String? = null,
    val birthDate: LocalDate? = null,
    val gender: String? = null,
    @field:Min(1) val gradeLevel: Int = 1,
    val groupId: UUID? = null,
    val enrolledAt: LocalDate? = null,
    val status: StudentStatus? = null,
    /** Ota-ona akkaunti bilan birga yaratish (docs/03-api-contract.md). */
    val createParentAccount: Boolean = false,
    val parent: ParentInput? = null,
)

data class StudentCreatedDto(
    val student: StudentDto,
    val parentCredentials: CredentialsDto? = null,
)

data class StudentPerformanceDto(
    val studentId: UUID,
    val period: PerformancePeriod,
    val academicScore: Int,
    val skillScore: Int,
    val knowledgeScore: Int,
    val attendanceRate: Int,
    val scoreDelta: Int,
    val bySubject: List<SubjectScoreDto>,
    val strengths: List<String>,
    val developing: List<String>,
    val needsAttention: List<String>,
    val trend: List<TrendPoint>,
)

data class SubjectDetailDto(
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val score: Int,
    val delta: Int,
    val strengths: List<String>,
    val developing: List<String>,
    val needsAttention: List<String>,
    val byTopic: List<TopicScoreDto>,
    val recentGrades: List<GradeDto>,
    val trend: List<TrendPoint>,
)

data class InterestDto(
    val id: UUID,
    val studentId: UUID,
    val key: String,
    val label: String,
    val description: String?,
    val level: Int,
    val colorKey: String,
    val iconKey: String,
    val source: String,
    val updatedAt: Instant,
)

data class InterestBody(
    @field:NotBlank val key: String = "",
    @field:NotBlank val label: String = "",
    val description: String? = null,
    val level: Int = 50,
    val colorKey: String = "blue",
    val iconKey: String = "star",
)

data class SkillScoreDto(val key: String, val label: String, val score: Int, val note: String? = null)

data class SkillAssessmentDto(
    val id: UUID,
    val studentId: UUID,
    val teacherId: UUID?,
    val teacherName: String,
    val date: LocalDate,
    val category: SkillCategory,
    val skills: List<SkillScoreDto>,
    val summary: String?,
    val source: String,
    val taskId: UUID?,
)

data class AchievementDto(
    val id: UUID,
    val studentId: UUID,
    val title: String,
    val description: String?,
    val date: LocalDate,
    val iconKey: String,
    val colorKey: String,
)

/* ---------------------------- Jadval va darslar ----------------------------- */

data class ScheduleSlotDto(
    val id: UUID,
    val schoolId: UUID,
    val groupId: UUID,
    val groupName: String,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val teacherId: UUID,
    val teacherName: String,
    val dayOfWeek: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val room: String?,
)

data class ScheduleSlotBody(
    val groupId: UUID,
    val subjectId: UUID,
    val teacherId: UUID,
    @field:Min(1) val dayOfWeek: Int = 1,
    val startTime: LocalTime = LocalTime.of(8, 30),
    val endTime: LocalTime = LocalTime.of(9, 15),
    val room: String? = null,
)

data class LessonTermDto(val term: String, val definition: String)

data class LessonDto(
    val id: UUID,
    val schoolId: UUID,
    val scheduleSlotId: UUID?,
    val groupId: UUID,
    val groupName: String,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val teacherId: UUID,
    val teacherName: String,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val room: String?,
    val topicId: UUID?,
    val title: String,
    val status: LessonStatus,
    val summary: String?,
    val terms: List<LessonTermDto>,
    val summaryStatus: SummaryStatus,
)

data class LessonUpdateBody(
    val title: String? = null,
    val topicId: UUID? = null,
    val teacherNotes: String? = null,
    val status: LessonStatus? = null,
)

data class LessonSummaryBody(
    val summary: String? = null,
    val terms: List<LessonTermDto>? = null,
)

/* ------------------------------ Baho / davomat ------------------------------ */

data class GradeDto(
    val id: UUID,
    val schoolId: UUID,
    val studentId: UUID,
    val studentName: String,
    val lessonId: UUID?,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val teacherId: UUID,
    val value: Double,
    val maxValue: Double,
    val type: GradeType,
    val topicId: UUID?,
    val topicTitle: String?,
    val comment: String?,
    val date: LocalDate,
    val sentToParentAt: Instant?,
)

data class GradeBody(
    val studentId: UUID,
    val subjectId: UUID,
    val lessonId: UUID? = null,
    val topicId: UUID? = null,
    @field:Min(0) val value: Double = 0.0,
    val maxValue: Double = 100.0,
    val type: GradeType = GradeType.CLASSWORK,
    val comment: String? = null,
    val date: LocalDate? = null,
)

data class BulkGradesBody(val grades: List<GradeBody> = emptyList())

data class GradebookCellDto(val studentId: UUID, val date: LocalDate, val grades: List<GradeDto>)

data class GradebookDto(
    val groupId: UUID,
    val subjectId: UUID,
    val dates: List<LocalDate>,
    val students: List<GradebookStudentDto>,
)

data class GradebookStudentDto(
    val studentId: UUID,
    val fullName: String,
    val average: Int,
    val cells: List<GradebookCellDto>,
)

data class AttendanceDto(
    val id: UUID,
    val schoolId: UUID,
    val studentId: UUID,
    val studentName: String,
    val lessonId: UUID,
    val subjectId: UUID,
    val date: LocalDate,
    val status: AttendanceStatus,
    val minutesLate: Int?,
    val reason: String?,
)

data class AttendanceBody(
    val studentId: UUID,
    val lessonId: UUID,
    val status: AttendanceStatus = AttendanceStatus.PRESENT,
    val minutesLate: Int? = null,
    val reason: String? = null,
)

data class BulkAttendanceBody(val records: List<AttendanceBody> = emptyList())

/* -------------------------------- Uy vazifasi ------------------------------- */

data class HomeworkDto(
    val id: UUID,
    val schoolId: UUID,
    val groupId: UUID,
    val groupName: String,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val teacherId: UUID,
    val teacherName: String,
    val lessonId: UUID?,
    val topicId: UUID?,
    val title: String,
    val description: String,
    val dueDate: LocalDate,
    val estimatedMinutes: Int,
    val scope: HomeworkScope,
    val targetStudentIds: List<UUID>,
    val aiGenerated: Boolean,
    val parentHint: String?,
    val difficulty: Difficulty,
    val createdAt: Instant,
    val sentToParentsAt: Instant?,
    val totalStudents: Int,
    val doneCount: Int,
    val variantCount: Int,
)

data class HomeworkBody(
    val groupId: UUID,
    val subjectId: UUID,
    val lessonId: UUID? = null,
    val topicId: UUID? = null,
    @field:NotBlank @field:Size(max = 200) val title: String = "",
    @field:NotBlank val description: String = "",
    val dueDate: LocalDate = LocalDate.now().plusDays(1),
    @field:Min(1) val estimatedMinutes: Int = 20,
    val scope: HomeworkScope = HomeworkScope.GROUP,
    val targetStudentIds: List<UUID> = emptyList(),
    val difficulty: Difficulty = Difficulty.NORMAL,
    val parentHint: String? = null,
)

data class HomeworkVariantDto(
    val id: UUID,
    val homeworkId: UUID,
    val studentId: UUID,
    val studentName: String,
    val simplifiedDescription: String,
    val parentHint: String?,
    val stepByStep: List<String>,
    val reason: String?,
    val aiGenerated: Boolean,
    val createdAt: Instant,
)

data class HomeworkVariantBody(
    val studentId: UUID,
    val simplifiedDescription: String = "",
    val parentHint: String? = null,
    val stepByStep: List<String> = emptyList(),
    val reason: String? = null,
)

data class HomeworkSubmissionDto(
    val id: UUID,
    val homeworkId: UUID,
    val studentId: UUID,
    val studentName: String,
    val avatarUrl: String?,
    val status: SubmissionStatus,
    val completedAt: Instant?,
    val parentConfirmed: Boolean,
    val teacherFeedback: String?,
    val grade: Double?,
)

data class SubmissionUpdateBody(
    val status: SubmissionStatus? = null,
    val teacherFeedback: String? = null,
    val grade: Double? = null,
)

/* ------------------------- AI generatsiya so'rovlari ------------------------ */

data class HomeworkGenerateBody(
    val subjectId: UUID,
    val topicId: UUID? = null,
    val topic: String? = null,
    @field:Min(1) val gradeLevel: Int = 1,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val estimatedMinutes: Int = 20,
)

data class HomeworkDraftDto(
    val title: String,
    val description: String,
    val estimatedMinutes: Int,
    val parentHint: String?,
    val terms: List<String>,
)

data class LessonSummaryDraftDto(
    val summary: String,
    val terms: List<LessonTermDto>,
)

/* ------------------------- Ko'nikma / tavsiya / AI -------------------------- */

data class AssessmentTaskDto(
    val id: UUID,
    val schoolId: UUID,
    val teacherId: UUID,
    val teacherName: String,
    val groupId: UUID?,
    val groupName: String?,
    val studentIds: List<UUID>,
    val title: String,
    val instruction: String,
    val category: SkillCategory,
    val expectedSignals: List<String>,
    val observationGuide: String?,
    val aiGenerated: Boolean,
    val status: AssessmentTaskStatus,
    val resultSummary: String?,
    val createdAt: Instant,
    val dueDate: LocalDate?,
)

data class AssessmentTaskBody(
    val groupId: UUID? = null,
    val studentIds: List<UUID> = emptyList(),
    @field:NotBlank val title: String = "",
    val instruction: String = "",
    val category: SkillCategory = SkillCategory.COGNITIVE,
    val expectedSignals: List<String> = emptyList(),
    val observationGuide: String? = null,
    val dueDate: LocalDate? = null,
)

data class AssessmentGenerateBody(
    @field:Min(1) val gradeLevel: Int = 1,
    val category: SkillCategory = SkillCategory.COGNITIVE,
    val groupId: UUID? = null,
)

data class ObservationBody(val studentId: UUID, val note: String)

data class ObservationsBody(val observations: List<ObservationBody> = emptyList())

data class RecommendationDto(
    val id: UUID,
    val schoolId: UUID?,
    val studentId: UUID,
    val studentName: String,
    val createdByAi: Boolean,
    val teacherId: UUID?,
    val area: CognitiveArea,
    val title: String,
    val description: String,
    val rationale: String,
    val steps: List<String>,
    val durationMinutes: Int,
    val difficulty: Difficulty,
    val status: RecommendationStatus,
    val assignedTo: String,
    val createdAt: Instant,
)

data class RecommendationStatusBody(val status: RecommendationStatus)

data class AiConversationDto(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val contextType: String,
    val contextId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AiMessageDto(
    val id: UUID,
    val conversationId: UUID,
    val author: String,
    val content: String,
    val suggestions: List<String>,
    val terms: List<String>,
    val createdAt: Instant,
)

data class AiConversationBody(
    val title: String? = null,
    val contextType: String = "GENERAL",
    val contextId: UUID? = null,
)

data class AiMessageBody(@field:NotBlank val content: String = "")

data class AiSearchBody(
    @field:NotBlank val query: String = "",
    val entityTypes: List<String> = emptyList(),
    val studentId: UUID? = null,
    val limit: Int = 10,
)

/* ------------------------- Xabar / bildirishnoma ---------------------------- */

data class MessageDto(
    val id: UUID,
    val schoolId: UUID?,
    val senderId: UUID?,
    val senderName: String,
    val senderRole: String?,
    val recipientType: String,
    val recipientLabel: String,
    val subject: String,
    val body: String,
    val category: MessageCategory,
    val colorKey: String,
    val sentAt: Instant,
    val isRead: Boolean,
)

data class MessageBody(
    val recipientType: String = "PARENT",
    val recipientIds: List<UUID> = emptyList(),
    val groupId: UUID? = null,
    val subject: String = "",
    @field:NotBlank val body: String = "",
    val category: MessageCategory = MessageCategory.GENERAL,
)

data class NotificationDto(
    val id: UUID,
    val userId: UUID,
    val type: String,
    val title: String,
    val body: String,
    val colorKey: String,
    val isRead: Boolean,
    val createdAt: Instant,
    val link: String?,
)
