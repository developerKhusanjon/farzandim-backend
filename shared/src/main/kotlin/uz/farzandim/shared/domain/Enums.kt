package uz.farzandim.shared.domain

/**
 * Domen enum'lari — docs/02-data-model.md.
 * Bazada `text` + CHECK sifatida saqlanadi (EnumType.STRING), shuning uchun
 * yangi qiymat qo'shilganda migratsiyada CHECK ham yangilanishi shart.
 */
enum class Role { SUPER_ADMIN, SCHOOL_ADMIN, TEACHER, PARENT, STUDENT }

enum class UserStatus { ACTIVE, INVITED, BLOCKED }

enum class Locale { uz, ru, en }

enum class GradingSystem { FIVE, HUNDRED, LETTER }

enum class SubscriptionPlanCode { BASIC, STANDARD, PREMIUM }

enum class SchoolStatus { ACTIVE, SUSPENDED, ARCHIVED }

enum class StudentStatus { ACTIVE, TRANSFERRED, GRADUATED }

enum class ParentRelation { MOTHER, FATHER, GUARDIAN }

enum class PreferredContact { PHONE, EMAIL, APP }

enum class WeekType { ALL, ODD, EVEN }

enum class LessonStatus { PLANNED, IN_PROGRESS, DONE, CANCELLED }

enum class SummaryStatus { NONE, AI_DRAFT, APPROVED, SENT_TO_PARENTS }

enum class GradeType { CLASSWORK, HOMEWORK, QUIZ, EXAM, PROJECT, ORAL }

enum class AttendanceStatus { PRESENT, ABSENT, LATE, EXCUSED }

enum class HomeworkScope { GROUP, INDIVIDUAL }

enum class Difficulty { EASY, NORMAL, HARD }

enum class SubmissionStatus { PENDING, DONE, LATE, SKIPPED }

enum class SkillCategory { COGNITIVE, INTEREST, BEHAVIOR, SOCIAL, CREATIVE }

enum class AssessmentTaskStatus { DRAFT, ASSIGNED, COLLECTED, ANALYZED }

enum class SkillSource { TEACHER_TASK, AI_ANALYSIS, OBSERVATION }

enum class InterestSource { TEACHER, AI, PARENT, SELF }

enum class CognitiveArea { LOGIC, MEMORY, ATTENTION, LANGUAGE, SPATIAL, CREATIVITY, EMOTIONAL }

enum class RecommendationStatus { SUGGESTED, ASSIGNED, IN_PROGRESS, DONE, DISMISSED }

enum class AssignedTo { STUDENT, PARENT }

enum class MessageCategory { GRADE, HOMEWORK, ATTENDANCE, ANNOUNCEMENT, LESSON_SUMMARY, AI, GENERAL }

enum class RecipientType { PARENT, GROUP_PARENTS, TEACHER, ALL }

enum class AiContextType { GENERAL, STUDENT, LESSON, HOMEWORK, REPORT }

enum class AiAuthor { USER, AI }

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

enum class FileStatus { PENDING, READY, FAILED, DELETED }

enum class ScanStatus { SKIPPED, PENDING, CLEAN, INFECTED }

enum class DevicePlatform { IOS, ANDROID, WEB }

enum class OtpChannel { PHONE, EMAIL }

enum class OtpPurpose { LOGIN, REGISTER, PASSWORD_RESET }

/** SMS va email uchun umumiy — ikkala kanal bir xil maqsadlarga xizmat qiladi. */
enum class MessagePurpose { OTP, CREDENTIALS, ANNOUNCEMENT, TEST }

enum class DeliveryStatus { QUEUED, SENT, DELIVERED, FAILED, REJECTED }

enum class DeliveryChannel { SMS, EMAIL }

enum class PerformancePeriod { WEEK, MONTH, SEMESTER, YEAR }

enum class SubscriptionStatus { PENDING, ACTIVE, EXPIRED, CANCELLED }

/** Obuna to'lovi — muddatdan alohida: maktab shartnoma bo'yicha ishlaydi, pul keyin keladi. */
enum class PaymentStatus { UNPAID, PAID, WAIVED }

/**
 * Maktab admini darajasi (faqat `SCHOOL_ADMIN` uchun).
 *
 *   SUPER   — maktab superadmini: boshqa adminlarni boshqaradi
 *   REGULAR — oddiy admin
 */
enum class SchoolAdminLevel { SUPER, REGULAR }

enum class SchoolNoteKind { NOTE, CALL, MEETING, CONTRACT, SUPPORT }

/** Token qaysi servis uchun berilgan — boshqa servisda ishlamaydi. */
enum class TokenAudience(val value: String) {
    SCHOOL("farzandim-school"),
    MOBILE("farzandim-mobile"),
    ADMIN("farzandim-admin"),
    ;

    companion object {
        fun from(value: String): TokenAudience? = entries.firstOrNull { it.value == value }
    }
}
