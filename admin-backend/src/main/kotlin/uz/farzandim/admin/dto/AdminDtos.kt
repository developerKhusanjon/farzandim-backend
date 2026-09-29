package uz.farzandim.admin.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import uz.farzandim.shared.domain.GradingSystem
import uz.farzandim.shared.domain.JobStatus
import uz.farzandim.shared.domain.Locale
import uz.farzandim.shared.domain.PaymentStatus
import uz.farzandim.shared.domain.SchoolAdminLevel
import uz.farzandim.shared.domain.SchoolNoteKind
import uz.farzandim.shared.domain.SchoolStatus
import uz.farzandim.shared.domain.SubscriptionPlanCode
import uz.farzandim.shared.domain.SubscriptionStatus
import uz.farzandim.shared.domain.UserStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Super admin paneli DTO'lari — `farzandim-admin` fronti shu shaklga qarab yozilgan.
 *
 * Bo'limlar ekranlarga mos: bosh sahifa, maktablar (ro'yxat, ulash, kartochka),
 * maktab ichki ma'lumoti (faqat o'qish), obuna va to'lov, tariflar,
 * foydalanuvchilar va jamoa, monitoring (AI, job, aloqa kanallari), audit.
 */

/* ================================ Maktablar ================================ */

data class SchoolRowDto(
    val id: UUID,
    val name: String,
    val shortName: String,
    val status: SchoolStatus,
    val statusReason: String?,
    val plan: SubscriptionPlanCode,
    val subscriptionUntil: LocalDate?,
    /** Obuna tugashiga necha kun qoldi (manfiy — tugagan). */
    val daysLeft: Long?,
    val region: String,
    val city: String,
    val studentCount: Long,
    val teacherCount: Long,
    val groupCount: Long,
    val parentCount: Long,
    val activeUsers30d: Long,
    val aiCalls30d: Long,
    /** To'lanmagan obunalar summasi (so'm). */
    val unpaidUzs: Long,
    /** Ishga tushirish bosqichlari: nechtasi bajarilgan / jami. */
    val onboardingDone: Int,
    val onboardingTotal: Int,
    val createdAt: Instant,
)

data class OnboardingStepDto(
    val key: String,
    val title: String,
    val done: Boolean,
    val count: Long,
)

data class SchoolFlagDto(
    val key: String,
    val description: String,
    val globalEnabled: Boolean,
    /** Shu maktab uchun alohida qiymat; `null` — umumiy qiymat ishlaydi. */
    val override: Boolean?,
    val effective: Boolean,
)

data class SchoolDetailDto(
    val school: SchoolRowDto,
    // Yuridik shaxs va shartnoma
    val legalName: String,
    val tin: String?,
    val directorName: String,
    val address: String,
    val phone: String,
    val email: String,
    val contactPerson: String,
    val contactPhone: String,
    val contractNumber: String?,
    val contractSignedOn: LocalDate?,
    // O'quv sozlamalari
    val logoUrl: String?,
    val academicYear: String,
    val gradingSystem: GradingSystem,
    val locale: Locale,
    val timezone: String,
    val aiDailyLimit: Int,
    val aiCallsToday: Long,
    val settings: Map<String, Any?>,
    // Huquqlar
    val planFeatures: Map<String, Boolean>,
    val flags: List<SchoolFlagDto>,
    // Odamlar va pul
    val admins: List<PlatformUserDto>,
    val subscriptions: List<SubscriptionDto>,
    val onboarding: List<OnboardingStepDto>,
    val updatedAt: Instant,
)

/** Yangi maktab bilan birga yaratiladigan admin. */
data class NewAdminBody(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    val phone: String? = null,
    @field:Email val email: String? = null,
    /** SUPER — maktab superadmini (boshqa adminlarni boshqaradi), REGULAR — oddiy admin. */
    val level: SchoolAdminLevel = SchoolAdminLevel.REGULAR,
)

data class CreateSchoolBody(
    @field:NotBlank val name: String = "",
    @field:NotBlank
    @field:Pattern(regexp = "^[a-zA-Z0-9._-]{2,40}$", message = "faqat lotin harf, raqam, nuqta, chiziqcha (2–40)")
    val shortName: String = "",
    // Yuridik shaxs va shartnoma
    val legalName: String = "",
    val tin: String? = null,
    val directorName: String = "",
    val region: String = "",
    val city: String = "",
    val address: String = "",
    val phone: String = "",
    @field:Email val email: String = "",
    val contactPerson: String = "",
    val contactPhone: String = "",
    val contractNumber: String? = null,
    val contractSignedOn: LocalDate? = null,
    // O'quv sozlamalari
    val academicYear: String = "",
    val gradingSystem: GradingSystem = GradingSystem.HUNDRED,
    val locale: Locale = Locale.uz,
    val timezone: String = "Asia/Tashkent",
    // Tarif va birinchi obuna
    val plan: SubscriptionPlanCode = SubscriptionPlanCode.BASIC,
    @field:Min(1) @field:Max(60) val subscriptionMonths: Int = 12,
    val subscriptionStartsOn: LocalDate? = null,
    /** Shartnomadagi o'quvchilar soni — yangi maktabda hali o'quvchi yo'q, narx shunga qarab hisoblanadi. */
    @field:Min(0) val contractStudents: Int? = null,
    @field:Min(0) @field:Max(100) val discountPercent: Int = 0,
    val paymentStatus: PaymentStatus = PaymentStatus.UNPAID,
    // Adminlar (kamida bittasi; SUPER bo'lmasa birinchisi SUPER qilinadi)
    @field:Valid val admins: List<NewAdminBody> = emptyList(),
    // Eski shakl (bitta admin) — orqaga moslik uchun
    val adminFirstName: String = "",
    val adminLastName: String = "",
    val adminPhone: String? = null,
    val adminEmail: String? = null,
)

data class AdminCredentialsDto(
    val userId: UUID,
    val fullName: String,
    val level: SchoolAdminLevel,
    val username: String,
    val password: String,
)

data class CreateSchoolResultDto(
    val school: SchoolDetailDto,
    /** Birinchi (superadmin) hisob — eski mijozlar uchun. */
    val adminCredentials: CredentialsDto,
    /** Barcha yaratilgan adminlar. Parollar faqat shu javobda ko'rinadi. */
    val credentials: List<AdminCredentialsDto>,
)

data class UpdateSchoolBody(
    val name: String? = null,
    @field:Pattern(regexp = "^[a-zA-Z0-9._-]{2,40}$", message = "faqat lotin harf, raqam, nuqta, chiziqcha (2–40)")
    val shortName: String? = null,
    val logoUrl: String? = null,
    val legalName: String? = null,
    val tin: String? = null,
    val directorName: String? = null,
    val region: String? = null,
    val city: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val contactPerson: String? = null,
    val contactPhone: String? = null,
    val contractNumber: String? = null,
    val contractSignedOn: LocalDate? = null,
    val academicYear: String? = null,
    val gradingSystem: GradingSystem? = null,
    val locale: Locale? = null,
    val timezone: String? = null,
    @field:Min(0) val aiDailyLimit: Int? = null,
    /** Maktab sozlamalari — berilgan kalitlar mavjudlariga qo'shiladi (merge). */
    val settings: Map<String, Any?>? = null,
)

data class StatusChangeBody(
    /** To'xtatish/arxivlash sababi — maktab admini kirishga urinsa ko'radi. */
    val reason: String? = null,
)

/* ======================= Maktab ichki ma'lumoti (o'qish) ======================= */

data class DailyActivityDto(
    val date: LocalDate,
    val lessonsDone: Long,
    val grades: Long,
    val attendanceMarks: Long,
    val homework: Long,
    val aiCalls: Long,
)

data class GradeLevelCountDto(val gradeLevel: Int, val students: Long)

data class SchoolOverviewDto(
    val schoolId: UUID,
    // Odamlar
    val students: Long,
    val teachers: Long,
    val groups: Long,
    val subjects: Long,
    val parents: Long,
    /** Ilovaga kamida bir marta kirgan ota-onalar. */
    val parentsActivated: Long,
    val admins: Long,
    val activeAdmins30d: Long,
    val activeTeachers30d: Long,
    val activeParents30d: Long,
    // 30 kunlik faollik
    val lessonsDone30d: Long,
    val lessonsPlanned30d: Long,
    val summariesSent30d: Long,
    val grades30d: Long,
    val homework30d: Long,
    val messages30d: Long,
    val attendanceRate30d: Double?,
    val averageScore30d: Double?,
    val aiCalls30d: Long,
    val aiCost30dUzs: Long,
    // Tafsilot
    val gradeLevels: List<GradeLevelCountDto>,
    val daily: List<DailyActivityDto>,
    val onboarding: List<OnboardingStepDto>,
)

data class StudentRowDto(
    val id: UUID,
    val fullName: String,
    val gradeLevel: Int,
    val groupId: UUID?,
    val groupName: String?,
    val status: String,
    val gender: String?,
    val birthDate: LocalDate?,
    val enrolledAt: LocalDate,
    val parentsCount: Long,
    val parentActivated: Boolean,
    val attendanceRate30d: Double?,
    val averageScore30d: Double?,
)

data class TeacherRowDto(
    val id: UUID,
    val userId: UUID,
    val fullName: String,
    val username: String?,
    val phone: String?,
    val status: UserStatus,
    val lastLoginAt: Instant?,
    val subjects: List<String>,
    val groups: List<String>,
    val lessonsDone30d: Long,
    val grades30d: Long,
)

data class GroupRowDto(
    val id: UUID,
    val name: String,
    val gradeLevel: Int,
    val academicYear: String,
    val classTeacherName: String?,
    val studentCount: Long,
    val attendanceRate30d: Double?,
    val averageScore30d: Double?,
)

data class SchoolNoteDto(
    val id: UUID,
    val schoolId: UUID,
    val kind: SchoolNoteKind,
    val body: String,
    val authorId: UUID?,
    val authorName: String?,
    val createdAt: Instant,
)

data class CreateNoteBody(
    val kind: SchoolNoteKind = SchoolNoteKind.NOTE,
    @field:NotBlank @field:Size(max = 4000) val body: String = "",
)

/* ========================== Foydalanuvchilar va jamoa ========================= */

data class PlatformUserDto(
    val id: UUID,
    val schoolId: UUID?,
    val schoolName: String?,
    val role: String,
    val adminLevel: SchoolAdminLevel?,
    val firstName: String,
    val lastName: String,
    val username: String?,
    val email: String?,
    val phone: String?,
    val status: UserStatus,
    val mustChangePassword: Boolean,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
)

data class CreateAdminBody(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    val phone: String? = null,
    @field:Email val email: String? = null,
    val level: SchoolAdminLevel = SchoolAdminLevel.REGULAR,
)

data class SetAdminLevelBody(val level: SchoolAdminLevel = SchoolAdminLevel.REGULAR)

data class CreateTeamMemberBody(
    @field:NotBlank val firstName: String = "",
    @field:NotBlank val lastName: String = "",
    @field:NotBlank @field:Email val email: String = "",
    val phone: String? = null,
)

data class CredentialsDto(
    val username: String,
    val password: String,
    val userId: UUID? = null,
    val note: String = "Parol faqat shu javobda ko'rsatiladi — saqlab qo'ying.",
)

/* ============================== Obuna va to'lov ============================== */

data class SubscriptionDto(
    val id: UUID,
    val schoolId: UUID,
    val schoolName: String?,
    val planCode: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val months: Int,
    val studentCount: Int,
    val amountUzs: Long,
    val monthlyUzs: Long,
    val discountPercent: Int,
    /** Bazadagi holat. */
    val status: SubscriptionStatus,
    /** Sanaga qarab: ACTIVE bo'lsa-yu muddati o'tgan bo'lsa — EXPIRED; hali boshlanmagan — PENDING. */
    val effectiveStatus: SubscriptionStatus,
    val paymentStatus: PaymentStatus,
    val paidOn: LocalDate?,
    val paymentMethod: String?,
    val paymentReference: String?,
    val note: String?,
    val cancelledAt: Instant?,
    val createdAt: Instant,
)

data class CreateSubscriptionBody(
    val planCode: SubscriptionPlanCode = SubscriptionPlanCode.BASIC,
    @field:Min(1) @field:Max(60) val months: Int = 12,
    /** Bo'sh bo'lsa: joriy obuna tugagan kundan (uzaytirish) yoki bugundan. */
    val startsOn: LocalDate? = null,
    /** Bo'sh bo'lsa — maktabdagi faol o'quvchilar soni. */
    @field:Min(0) val studentCount: Int? = null,
    @field:Min(0) @field:Max(100) val discountPercent: Int = 0,
    /** Qo'lda kelishilgan summa — formula o'rniga. */
    @field:Min(0) val amountUzs: Long? = null,
    val paymentStatus: PaymentStatus = PaymentStatus.UNPAID,
    val paidOn: LocalDate? = null,
    val paymentMethod: String? = null,
    val paymentReference: String? = null,
    val note: String? = null,
)

data class MarkPaidBody(
    val paidOn: LocalDate? = null,
    /** naqd | bank | payme | click | boshqa */
    val method: String? = null,
    val reference: String? = null,
    /** true — to'lovdan ozod (masalan, sinov davri). */
    val waived: Boolean = false,
)

data class CancelSubscriptionBody(val reason: String? = null)

data class BillingMonthDto(
    val month: String,
    val invoicedUzs: Long,
    val paidUzs: Long,
)

data class BillingSummaryDto(
    val monthlyRecurringUzs: Long,
    val paidThisMonthUzs: Long,
    val paidThisYearUzs: Long,
    val unpaidUzs: Long,
    val unpaidCount: Long,
    val months: List<BillingMonthDto>,
)

/* ================================= Tariflar ================================= */

data class PlanDto(
    val code: String,
    val title: String,
    val pricePerStudentUzs: Long,
    val minMonthlyUzs: Long,
    val maxStudents: Int?,
    val aiDailyLimit: Int,
    val features: Map<String, Any?>,
    val active: Boolean,
    /** Shu tarifdagi maktablar soni. */
    val schools: Long,
)

data class UpdatePlanBody(
    val title: String? = null,
    @field:Min(0) val pricePerStudentUzs: Long? = null,
    @field:Min(0) val minMonthlyUzs: Long? = null,
    /** 0 yoki manfiy — chegara yo'q. */
    val maxStudents: Int? = null,
    @field:Min(0) val aiDailyLimit: Int? = null,
    val active: Boolean? = null,
    /** Berilgan kalitlar almashtiriladi: `{"aiChat": true}`. */
    val features: Map<String, Boolean>? = null,
    /** true — shu tarifdagi barcha maktablarning AI limitini yangi qiymatga tenglash. */
    val applyAiLimitToSchools: Boolean = false,
)

/* ================================ Statistika ================================ */

data class PlatformStatsDto(
    val schools: Long,
    val activeSchools: Long,
    val suspendedSchools: Long,
    val archivedSchools: Long,
    val newSchools30d: Long,
    val students: Long,
    val teachers: Long,
    val parents: Long,
    val parentsActivated: Long,
    val activeUsers30d: Long,
    val expiringIn30Days: Long,
    val expired: Long,
    val monthlyRecurringUzs: Long,
    val unpaidUzs: Long,
    val paidThisMonthUzs: Long,
    val aiCallsToday: Long,
    val aiCostTodayUzs: Long,
    val aiCalls30d: Long,
    val aiCost30dUzs: Long,
    val jobsQueued: Long,
    val jobsFailed24h: Long,
)

data class TrendPointDto(
    /** `2026-09` */
    val month: String,
    val newSchools: Long,
    val totalSchools: Long,
    val newStudents: Long,
    val invoicedUzs: Long,
    val paidUzs: Long,
    val aiCalls: Long,
    val aiCostUzs: Long,
)

data class AlertDto(
    /** SUBSCRIPTION_EXPIRING | SUBSCRIPTION_EXPIRED | UNPAID | ONBOARDING_STALLED | INACTIVE | AI_QUOTA | JOBS_FAILED | NO_ADMIN */
    val type: String,
    /** HIGH | MEDIUM | LOW */
    val severity: String,
    val schoolId: UUID?,
    val schoolName: String?,
    val message: String,
    val value: Long?,
)

data class SchoolMetricsDto(
    val schoolId: UUID,
    val name: String,
    val status: SchoolStatus,
    val plan: SubscriptionPlanCode,
    val students: Long,
    val teachers: Long,
    val groups: Long,
    val parentsActivated: Long,
    val activeUsers30d: Long,
    val lessonsDone30d: Long,
    val summariesSent30d: Long,
    val grades30d: Long,
    val homework30d: Long,
    val attendanceRate30d: Double?,
    val averageScore30d: Double?,
    val aiCalls30d: Long,
    val aiCost30dUzs: Long,
)

/* ============================== AI va monitoring ============================= */

data class AiUsageRowDto(
    val schoolId: UUID,
    val schoolName: String,
    val usageDate: LocalDate,
    val feature: String,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUzs: Long,
)

data class AiBucketDto(val key: String, val label: String, val calls: Long, val costUzs: Long, val tokens: Long)

data class AiSummaryDto(
    val days: Int,
    val totalCalls: Long,
    val totalCostUzs: Long,
    val totalTokens: Long,
    val byFeature: List<AiBucketDto>,
    val bySchool: List<AiBucketDto>,
    val byDay: List<AiBucketDto>,
)

data class JobRowDto(
    val id: UUID,
    val schoolId: UUID?,
    val schoolName: String?,
    val type: String,
    val status: JobStatus,
    val attempts: Int,
    val errorCode: String?,
    val errorMessage: String?,
    val model: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

data class FeatureFlagDto(
    val key: String,
    val description: String,
    val enabled: Boolean,
    val schoolOverrides: Map<String, Boolean>,
    val overrides: List<FlagOverrideDto>,
    val updatedAt: Instant,
)

data class FlagOverrideDto(val schoolId: UUID, val schoolName: String?, val enabled: Boolean)

data class UpdateFlagBody(
    val enabled: Boolean? = null,
    val schoolId: UUID? = null,
    val schoolEnabled: Boolean? = null,
    /** true — shu maktab uchun istisnoni olib tashlash (umumiy qiymat ishlaydi). */
    val clearSchool: Boolean = false,
)

data class SmsRowDto(
    val id: UUID,
    val schoolId: UUID?,
    val provider: String,
    val recipient: String,
    val purpose: String,
    val parts: Int,
    val status: String,
    val errorCode: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
    val deliveredAt: Instant?,
)

data class DeliveryStatusDto(
    val smsProvider: String,
    val smsConfigured: Boolean,
    val smsCredentialsEnabled: Boolean,
    val pushProvider: String,
    val pushConfigured: Boolean,
    val smsSentToday: Long,
    val smsPartsToday: Long,
    val smsFailedToday: Long,
    val emailProvider: String,
    val emailConfigured: Boolean,
    val emailSentToday: Long,
    val emailFailedToday: Long,
    val activeDevices: Long,
)

data class EmailRowDto(
    val id: UUID,
    val schoolId: UUID?,
    val provider: String,
    val recipient: String,
    val subject: String,
    val purpose: String,
    val status: String,
    val errorCode: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
)

data class TestEmailBody(
    @field:NotBlank val email: String = "",
    val subject: String = "Farzandim AI: sinov xabari",
    val text: String = "Email kanali ishlayapti.",
)

data class TestSmsBody(
    @field:NotBlank val phone: String = "",
    val text: String = "Farzandim AI: sinov xabari. Shlyuz ishlayapti.",
)

/* ================================== E'lonlar ================================= */

data class AnnouncementBody(
    @field:NotBlank @field:Size(max = 200) val title: String = "",
    @field:NotBlank @field:Size(max = 4000) val body: String = "",
    /** Bo'sh — barcha faol maktablar. */
    val schoolIds: List<UUID> = emptyList(),
    /** ADMINS — faqat maktab adminlari; STAFF — adminlar va o'qituvchilar. */
    @field:Pattern(regexp = "ADMINS|STAFF") val audience: String = "ADMINS",
)

data class AnnouncementResultDto(val schools: Int, val recipients: Int)

/* =================================== Audit ================================== */

data class AuditRowDto(
    val id: Long,
    val schoolId: UUID?,
    val schoolName: String?,
    val actorId: UUID?,
    val actorName: String?,
    val actorRole: String?,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val payload: Map<String, Any?>,
    val ipAddress: String?,
    val createdAt: Instant,
)
