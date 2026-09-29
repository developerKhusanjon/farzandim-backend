package uz.farzandim.mobile.dto

import jakarta.validation.constraints.NotBlank
import uz.farzandim.shared.domain.CognitiveArea
import uz.farzandim.shared.domain.Difficulty
import uz.farzandim.shared.domain.Locale
import uz.farzandim.shared.domain.ParentRelation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Mobil DTO'lar — farzandim-mobile/src/api/types.ts bilan bir xil.
 *
 * Bu web CRM DTO'laridan ataylab farq qiladi: ota-onaga boshqa kesim
 * kerak. Masalan, ota-ona guruhning o'rtacha ballini ko'rmaydi —
 * faqat o'z farzandining natijasini ko'radi.
 */

data class ParentUserDto(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?,
    val phone: String?,
    val email: String?,
    /** `null` — maktabga bog'lanmagan mustaqil foydalanuvchi (faqat AI). */
    val schoolId: UUID?,
    val relation: ParentRelation,
    val locale: Locale,
)

data class SchoolBriefDto(
    val id: UUID,
    val name: String,
    val address: String,
    val photoUrl: String?,
    val phone: String,
    val academicYear: String,
)

data class ChildDto(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?,
    val gradeLevel: Int,
    val groupName: String,
    val birthDate: LocalDate?,
    val status: String,
    /** Bugun maktabdami — bosh sahifadagi holat chipi. */
    val atSchool: Boolean,
    val academicScore: Int,
    val academicDelta: Int,
    val activityScore: Int,
    val activityDelta: Int,
    val attendanceRate: Int,
    val attendanceDelta: Int,
    val interestsCount: Int,
    val weaknessesCount: Int,
)

data class SubjectScoreDto(
    val subjectId: UUID,
    val name: String,
    val shortName: String,
    val colorKey: String,
    val score: Int,
    val delta: Int,
    val teacherName: String,
)

data class TopicScoreDto(val topicId: UUID, val title: String, val score: Int)

data class RecentGradeDto(
    val id: UUID,
    val date: LocalDate,
    val type: String,
    val value: Double,
    val topic: String?,
)

data class TrendPointDto(val label: String, val value: Int)

data class SubjectDetailDto(
    val subjectId: UUID,
    val name: String,
    val shortName: String,
    val colorKey: String,
    val score: Int,
    val delta: Int,
    val teacherName: String,
    val byTopic: List<TopicScoreDto>,
    val strengths: List<String>,
    val needsWork: List<String>,
    val recentGrades: List<RecentGradeDto>,
    val trend: List<TrendPointDto>,
    val teacherComment: String?,
)

data class ProgressTrendDto(
    val period: String,
    val rangeLabel: String,
    val points: List<TrendPointDto>,
    val average: Int,
    val delta: Int,
)

data class CharacterTraitDto(
    val key: String,
    val label: String,
    val score: Int,
    val description: String,
)

data class CharacterGroupDto(
    val category: String,
    val label: String,
    val colorKey: String,
    val score: Int,
    val traits: List<CharacterTraitDto>,
    val summary: String,
    val updatedAt: Instant?,
    val source: String,
)

data class StrengthItemDto(val title: String, val detail: String, val colorKey: String)

data class StrengthWeaknessDto(
    val strengths: List<StrengthItemDto>,
    val weaknesses: List<StrengthItemDto>,
    val summary: String,
)

data class InterestDto(
    val id: UUID,
    val key: String,
    val label: String,
    val description: String,
    val level: Int,
    val colorKey: String,
    val iconKey: String,
    val source: String,
)

data class AbilityDto(
    val id: UUID,
    val label: String,
    val description: String,
    val level: Int,
    val colorKey: String,
    val iconKey: String,
)

data class DayTaskDto(
    val id: UUID,
    val kind: String,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val time: String,
    val label: String,
    val done: Boolean,
)

data class AdaptedVariantDto(
    val reason: String,
    val description: String,
    val stepByStep: List<String>,
    val parentHint: String,
)

data class HomeworkDto(
    val id: UUID,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val title: String,
    val description: String,
    val dueDate: LocalDate,
    val estimatedMinutes: Int,
    val difficulty: Difficulty,
    val status: String,
    val teacherName: String,
    val parentHint: String?,
    /** O'qituvchi aynan shu bola uchun tasdiqlagan soddalashtirilgan variant. */
    val adaptedVariant: AdaptedVariantDto?,
)

data class LessonTermDto(val term: String, val definition: String)

data class LessonSummaryDto(
    val id: UUID,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val date: LocalDate,
    val topic: String,
    val teacherName: String,
    val summary: String,
    val terms: List<LessonTermDto>,
)

data class DashboardDto(
    val child: ChildDto,
    val tasks: List<DayTaskDto>,
    val lastLesson: LessonSummaryDto?,
    val unreadMessages: Long,
)

data class CognitiveTaskDto(
    val id: UUID,
    val area: CognitiveArea,
    val areaLabel: String,
    val colorKey: String,
    val title: String,
    val description: String,
    val rationale: String,
    val steps: List<String>,
    val durationMinutes: Int,
    val difficulty: Difficulty,
    val status: String,
    val withParent: Boolean,
    val createdAt: Instant,
)

data class MessageLinkDto(val screen: String, val id: UUID)

data class MessageDto(
    val id: UUID,
    val category: String,
    val colorKey: String,
    val iconKey: String,
    val title: String,
    val body: String,
    val sentAt: Instant,
    val isRead: Boolean,
    val link: MessageLinkDto?,
)

data class TeacherDto(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?,
    val subjectName: String,
    val colorKey: String,
    val groups: List<String>,
    val phone: String?,
    val email: String?,
)

data class ScheduleItemDto(
    val id: UUID,
    val dayOfWeek: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val subjectId: UUID,
    val subjectName: String,
    val colorKey: String,
    val teacherName: String,
    val room: String?,
)

/* --------------------------------- AI ------------------------------------- */

data class AiHomeworkBody(
    /** SIMPLIFY | EXPLAIN | SOLVE */
    @field:NotBlank val mode: String = "SIMPLIFY",
)

data class AiHomeworkResultDto(
    val mode: String,
    /** SOLVE rejimida majburiy ogohlantirish. */
    val warning: String?,
    val content: String,
    val steps: List<String>,
)

data class AiChatBody(
    @field:NotBlank val content: String = "",
    val childId: UUID? = null,
)

data class AiMessageDto(
    val id: UUID,
    val author: String,
    val content: String,
    val terms: List<String>,
    val createdAt: Instant,
)

/* ------------------------------ Boshqalar --------------------------------- */

data class HomeworkStatusBody(
    /** PENDING | DONE | MISSED */
    val status: String = "DONE",
)

data class DeviceBody(
    @field:NotBlank val token: String = "",
    val platform: String = "ANDROID",
    val locale: String = "uz",
    val appVersion: String? = null,
)

data class RecommendationStatusBody(
    /** SUGGESTED | IN_PROGRESS | DONE */
    val status: String = "IN_PROGRESS",
)
