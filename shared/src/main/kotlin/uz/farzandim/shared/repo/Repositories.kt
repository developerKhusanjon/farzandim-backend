package uz.farzandim.shared.repo

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import uz.farzandim.shared.domain.Achievement
import uz.farzandim.shared.domain.AiConversation
import uz.farzandim.shared.domain.AiJob
import uz.farzandim.shared.domain.AiMessage
import uz.farzandim.shared.domain.AssessmentTask
import uz.farzandim.shared.domain.Attendance
import uz.farzandim.shared.domain.AuditEntry
import uz.farzandim.shared.domain.CognitiveRecommendation
import uz.farzandim.shared.domain.DeviceToken
import uz.farzandim.shared.domain.EmailMessage
import uz.farzandim.shared.domain.FeatureFlag
import uz.farzandim.shared.domain.FileObject
import uz.farzandim.shared.domain.Grade
import uz.farzandim.shared.domain.Homework
import uz.farzandim.shared.domain.HomeworkSubmission
import uz.farzandim.shared.domain.HomeworkVariant
import uz.farzandim.shared.domain.Interest
import uz.farzandim.shared.domain.Lesson
import uz.farzandim.shared.domain.MessageEntity
import uz.farzandim.shared.domain.MessageRecipient
import uz.farzandim.shared.domain.MessageRecipientId
import uz.farzandim.shared.domain.Notification
import uz.farzandim.shared.domain.OtpCode
import uz.farzandim.shared.domain.ParentProfile
import uz.farzandim.shared.domain.RefreshToken
import uz.farzandim.shared.domain.ScheduleSlot
import uz.farzandim.shared.domain.School
import uz.farzandim.shared.domain.SchoolNote
import uz.farzandim.shared.domain.SchoolSubscription
import uz.farzandim.shared.domain.SkillAssessment
import uz.farzandim.shared.domain.SmsMessage
import uz.farzandim.shared.domain.Student
import uz.farzandim.shared.domain.StudentParent
import uz.farzandim.shared.domain.StudentParentId
import uz.farzandim.shared.domain.StudentPerformance
import uz.farzandim.shared.domain.StudentPerformanceId
import uz.farzandim.shared.domain.StudyGroup
import uz.farzandim.shared.domain.Subject
import uz.farzandim.shared.domain.SubscriptionPlan
import uz.farzandim.shared.domain.Teacher
import uz.farzandim.shared.domain.TeacherAssignment
import uz.farzandim.shared.domain.Topic
import uz.farzandim.shared.domain.UserAccount
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/* ------------------------------ Identifikatsiya ---------------------------- */

@ApplicationScoped
class SchoolRepository : PanacheRepositoryBase<School, UUID> {
    fun findByShortName(shortName: String): School? =
        find("lower(shortName) = ?1", shortName.lowercase()).firstResult()
}

@ApplicationScoped
class UserRepository : PanacheRepositoryBase<UserAccount, UUID> {

    /** Login — bitta maydon: username (maktab ichida), email yoki telefon. */
    fun findByLogin(login: String): UserAccount? {
        val normalized = login.trim()
        return find(
            "lower(username) = ?1 or lower(email) = ?1 or phone = ?2",
            normalized.lowercase(),
            normalized,
        ).firstResult()
    }

    fun findByEmailOrPhone(value: String): UserAccount? =
        find("lower(email) = ?1 or phone = ?2", value.lowercase(), value).firstResult()

    /** Login butun platformada unikal — V1 migratsiyasidagi izohga qarang. */
    fun usernameTaken(username: String): Boolean =
        count("lower(username) = ?1", username.lowercase()) > 0

    fun listBySchoolAndRole(schoolId: UUID, role: uz.farzandim.shared.domain.Role): List<UserAccount> =
        list("schoolId = ?1 and role = ?2 order by createdAt", schoolId, role)

    /** Maktabda faol (bloklanmagan) superadminlar soni — oxirgisini o'chirib qo'ymaslik uchun. */
    fun countActiveSchoolSuperAdmins(schoolId: UUID): Long =
        count(
            "schoolId = ?1 and role = ?2 and status <> ?3 and (adminLevel is null or adminLevel = ?4)",
            schoolId,
            uz.farzandim.shared.domain.Role.SCHOOL_ADMIN,
            uz.farzandim.shared.domain.UserStatus.BLOCKED,
            uz.farzandim.shared.domain.SchoolAdminLevel.SUPER,
        )

    fun findAllByIds(ids: Collection<UUID>): List<UserAccount> =
        if (ids.isEmpty()) emptyList() else list("id in ?1", ids)
}

@ApplicationScoped
class RefreshTokenRepository : PanacheRepositoryBase<RefreshToken, UUID> {
    fun findByHash(hash: String): RefreshToken? = find("tokenHash", hash).firstResult()

    fun revokeFamily(familyId: UUID, reason: String): Int =
        update(
            "revokedAt = ?1, revokeReason = ?2 where familyId = ?3 and revokedAt is null",
            Instant.now(), reason, familyId,
        )

    fun revokeAllForUser(userId: UUID, reason: String): Int =
        update(
            "revokedAt = ?1, revokeReason = ?2 where userId = ?3 and revokedAt is null",
            Instant.now(), reason, userId,
        )

    fun deleteExpired(before: Instant): Long = delete("expiresAt < ?1", before)
}

@ApplicationScoped
class OtpRepository : PanacheRepositoryBase<OtpCode, UUID> {
    fun findLatest(recipient: String, purpose: uz.farzandim.shared.domain.OtpPurpose): OtpCode? =
        find(
            "recipient = ?1 and purpose = ?2 and consumedAt is null order by createdAt desc",
            recipient, purpose,
        ).firstResult()

    fun deleteExpired(before: Instant): Long = delete("expiresAt < ?1", before)
}

@ApplicationScoped
class DeviceTokenRepository : PanacheRepositoryBase<DeviceToken, UUID> {
    fun findByToken(token: String): DeviceToken? = find("token", token).firstResult()

    /** Faqat faol qurilmalar — o'chirilganlariga push yuborilmaydi. */
    fun listForUsers(userIds: Collection<UUID>): List<DeviceToken> =
        if (userIds.isEmpty()) emptyList()
        else list("userId in ?1 and disabledAt is null", userIds)

    fun deleteByToken(token: String): Long = delete("token", token)
}

@ApplicationScoped
class SmsRepository : PanacheRepositoryBase<SmsMessage, UUID> {
    fun findByProviderId(provider: String, providerMessageId: String): SmsMessage? =
        find("provider = ?1 and providerMessageId = ?2", provider, providerMessageId).firstResult()

    fun countSince(recipient: String, since: java.time.Instant): Long =
        count("recipient = ?1 and createdAt >= ?2", recipient, since)

    fun listForSchool(schoolId: UUID, limit: Int): List<SmsMessage> =
        find("schoolId = ?1 order by createdAt desc", schoolId).page(0, limit).list()
}

@ApplicationScoped
class EmailRepository : PanacheRepositoryBase<EmailMessage, UUID> {
    fun countSince(recipient: String, since: java.time.Instant): Long =
        count("lower(recipient) = ?1 and createdAt >= ?2", recipient.lowercase(), since)

    fun listForSchool(schoolId: UUID, limit: Int): List<EmailMessage> =
        find("schoolId = ?1 order by createdAt desc", schoolId).page(0, limit).list()
}

@ApplicationScoped
class AuditRepository : PanacheRepository<AuditEntry>

@ApplicationScoped
class SchoolNoteRepository : PanacheRepositoryBase<SchoolNote, UUID> {
    fun listForSchool(schoolId: UUID, limit: Int): List<SchoolNote> =
        find("schoolId = ?1 order by createdAt desc", schoolId).page(0, limit).list()
}

/* --------------------------------- O'quv qism ------------------------------ */

@ApplicationScoped
class SubjectRepository : TenantRepository<Subject> {
    fun listActive(schoolId: UUID): List<Subject> =
        list("schoolId = ?1 and archived = false order by name", schoolId)
}

@ApplicationScoped
class TopicRepository : TenantRepository<Topic> {
    fun listBySubject(schoolId: UUID, subjectId: UUID, gradeLevel: Int?): List<Topic> =
        if (gradeLevel == null) {
            list("schoolId = ?1 and subjectId = ?2 order by gradeLevel, orderIndex", schoolId, subjectId)
        } else {
            list(
                "schoolId = ?1 and subjectId = ?2 and gradeLevel = ?3 order by orderIndex",
                schoolId, subjectId, gradeLevel,
            )
        }
}

@ApplicationScoped
class GroupRepository : TenantRepository<StudyGroup> {
    fun listActive(schoolId: UUID): List<StudyGroup> =
        list("schoolId = ?1 and archived = false order by gradeLevel, name", schoolId)

    fun findByName(schoolId: UUID, academicYear: String, name: String): StudyGroup? =
        find(
            "schoolId = ?1 and academicYear = ?2 and lower(name) = ?3",
            schoolId, academicYear, name.lowercase(),
        ).firstResult()
}

@ApplicationScoped
class TeacherRepository : TenantRepository<Teacher> {
    fun findByUserId(userId: UUID): Teacher? = find("userId", userId).firstResult()

    fun findAllByIds(ids: Collection<UUID>): List<Teacher> =
        if (ids.isEmpty()) emptyList() else list("id in ?1", ids)
}

@ApplicationScoped
class StudentRepository : TenantRepository<Student> {
    fun listByGroup(schoolId: UUID, groupId: UUID): List<Student> =
        list(
            "schoolId = ?1 and groupId = ?2 and status = ?3 order by lastName, firstName",
            schoolId, groupId, uz.farzandim.shared.domain.StudentStatus.ACTIVE,
        )

    fun findAllByIds(schoolId: UUID, ids: Collection<UUID>): List<Student> =
        if (ids.isEmpty()) emptyList() else list("schoolId = ?1 and id in ?2", schoolId, ids)

    fun countActive(schoolId: UUID): Long =
        count("schoolId = ?1 and status = ?2", schoolId, uz.farzandim.shared.domain.StudentStatus.ACTIVE)
}

@ApplicationScoped
class ParentRepository : PanacheRepositoryBase<ParentProfile, UUID> {
    fun findByUserId(userId: UUID): ParentProfile? = find("userId", userId).firstResult()

    fun listInSchool(schoolId: UUID): List<ParentProfile> = list("schoolId", schoolId)

    fun findAllByIds(ids: Collection<UUID>): List<ParentProfile> =
        if (ids.isEmpty()) emptyList() else list("id in ?1", ids)
}

@ApplicationScoped
class StudentParentRepository : PanacheRepositoryBase<StudentParent, StudentParentId> {
    fun listByStudent(studentId: UUID): List<StudentParent> = list("id.studentId", studentId)

    fun listByParent(parentId: UUID): List<StudentParent> = list("id.parentId", parentId)

    fun listByStudents(studentIds: Collection<UUID>): List<StudentParent> =
        if (studentIds.isEmpty()) emptyList() else list("id.studentId in ?1", studentIds)

    fun exists(parentId: UUID, studentId: UUID): Boolean =
        count("id.parentId = ?1 and id.studentId = ?2", parentId, studentId) > 0
}

@ApplicationScoped
class TeacherAssignmentRepository : TenantRepository<TeacherAssignment> {
    fun listByTeacher(schoolId: UUID, teacherId: UUID): List<TeacherAssignment> =
        list("schoolId = ?1 and teacherId = ?2", schoolId, teacherId)

    fun listByGroup(schoolId: UUID, groupId: UUID): List<TeacherAssignment> =
        list("schoolId = ?1 and groupId = ?2", schoolId, groupId)

    fun teaches(teacherId: UUID, groupId: UUID, subjectId: UUID): Boolean =
        count("teacherId = ?1 and groupId = ?2 and subjectId = ?3", teacherId, groupId, subjectId) > 0

    fun teachesGroup(teacherId: UUID, groupId: UUID): Boolean =
        count("teacherId = ?1 and groupId = ?2", teacherId, groupId) > 0
}

@ApplicationScoped
class ScheduleSlotRepository : TenantRepository<ScheduleSlot> {
    fun listByGroup(schoolId: UUID, groupId: UUID): List<ScheduleSlot> =
        list("schoolId = ?1 and groupId = ?2 order by dayOfWeek, startTime", schoolId, groupId)

    fun listByTeacher(schoolId: UUID, teacherId: UUID): List<ScheduleSlot> =
        list("schoolId = ?1 and teacherId = ?2 order by dayOfWeek, startTime", schoolId, teacherId)

    fun listForDay(schoolId: UUID, dayOfWeek: Int): List<ScheduleSlot> =
        list("schoolId = ?1 and dayOfWeek = ?2 order by startTime", schoolId, dayOfWeek)
}

@ApplicationScoped
class LessonRepository : TenantRepository<Lesson> {
    fun listByDate(schoolId: UUID, date: LocalDate): List<Lesson> =
        list("schoolId = ?1 and lessonDate = ?2 order by startTime", schoolId, date)

    fun listByGroupAndRange(schoolId: UUID, groupId: UUID, from: LocalDate, to: LocalDate): List<Lesson> =
        list(
            "schoolId = ?1 and groupId = ?2 and lessonDate between ?3 and ?4 order by lessonDate, startTime",
            schoolId, groupId, from, to,
        )

    fun listByTeacherAndDate(schoolId: UUID, teacherId: UUID, date: LocalDate): List<Lesson> =
        list("schoolId = ?1 and teacherId = ?2 and lessonDate = ?3 order by startTime", schoolId, teacherId, date)

    fun findBySlotAndDate(slotId: UUID, date: LocalDate): Lesson? =
        find("scheduleSlotId = ?1 and lessonDate = ?2", slotId, date).firstResult()

    fun lastSentSummary(schoolId: UUID, groupId: UUID): Lesson? =
        find(
            "schoolId = ?1 and groupId = ?2 and summaryStatus = ?3 order by lessonDate desc, startTime desc",
            schoolId, groupId, uz.farzandim.shared.domain.SummaryStatus.SENT_TO_PARENTS,
        ).firstResult()
}

/* ------------------------------ Baho va davomat ---------------------------- */

@ApplicationScoped
class GradeRepository : TenantRepository<Grade> {
    fun listByStudent(schoolId: UUID, studentId: UUID, from: LocalDate?, to: LocalDate?): List<Grade> =
        if (from != null && to != null) {
            list(
                "schoolId = ?1 and studentId = ?2 and gradedOn between ?3 and ?4 order by gradedOn desc",
                schoolId, studentId, from, to,
            )
        } else {
            list("schoolId = ?1 and studentId = ?2 order by gradedOn desc", schoolId, studentId)
        }

    fun listByStudentAndSubject(schoolId: UUID, studentId: UUID, subjectId: UUID): List<Grade> =
        list(
            "schoolId = ?1 and studentId = ?2 and subjectId = ?3 order by gradedOn desc",
            schoolId, studentId, subjectId,
        )

    fun listByLesson(schoolId: UUID, lessonId: UUID): List<Grade> =
        list("schoolId = ?1 and lessonId = ?2", schoolId, lessonId)

    fun listForStudents(schoolId: UUID, studentIds: Collection<UUID>, from: LocalDate): List<Grade> =
        if (studentIds.isEmpty()) emptyList()
        else list("schoolId = ?1 and studentId in ?2 and gradedOn >= ?3", schoolId, studentIds, from)
}

@ApplicationScoped
class AttendanceRepository : TenantRepository<Attendance> {
    fun listByStudent(schoolId: UUID, studentId: UUID, from: LocalDate, to: LocalDate): List<Attendance> =
        list(
            "schoolId = ?1 and studentId = ?2 and attendedOn between ?3 and ?4 order by attendedOn desc",
            schoolId, studentId, from, to,
        )

    fun listByLesson(schoolId: UUID, lessonId: UUID): List<Attendance> =
        list("schoolId = ?1 and lessonId = ?2", schoolId, lessonId)

    fun findForStudentLesson(studentId: UUID, lessonId: UUID): Attendance? =
        find("studentId = ?1 and lessonId = ?2", studentId, lessonId).firstResult()
}

/* ------------------------------- Uy vazifasi ------------------------------- */

@ApplicationScoped
class HomeworkRepository : TenantRepository<Homework> {
    fun listByGroup(schoolId: UUID, groupId: UUID, from: LocalDate?, to: LocalDate?): List<Homework> =
        when {
            from != null && to != null -> list(
                "schoolId = ?1 and groupId = ?2 and dueDate between ?3 and ?4 order by dueDate desc",
                schoolId, groupId, from, to,
            )
            else -> list("schoolId = ?1 and groupId = ?2 order by dueDate desc", schoolId, groupId)
        }

    fun listSentForGroup(schoolId: UUID, groupId: UUID): List<Homework> =
        list(
            "schoolId = ?1 and groupId = ?2 and sentToParentsAt is not null order by dueDate desc",
            schoolId, groupId,
        )
}

@ApplicationScoped
class HomeworkVariantRepository : TenantRepository<HomeworkVariant> {
    fun listByHomework(schoolId: UUID, homeworkId: UUID): List<HomeworkVariant> =
        list("schoolId = ?1 and homeworkId = ?2", schoolId, homeworkId)

    fun findForStudent(homeworkId: UUID, studentId: UUID): HomeworkVariant? =
        find("homeworkId = ?1 and studentId = ?2", homeworkId, studentId).firstResult()
}

@ApplicationScoped
class HomeworkSubmissionRepository : TenantRepository<HomeworkSubmission> {
    fun listByHomework(schoolId: UUID, homeworkId: UUID): List<HomeworkSubmission> =
        list("schoolId = ?1 and homeworkId = ?2", schoolId, homeworkId)

    fun findForStudent(homeworkId: UUID, studentId: UUID): HomeworkSubmission? =
        find("homeworkId = ?1 and studentId = ?2", homeworkId, studentId).firstResult()

    fun listForStudent(schoolId: UUID, studentId: UUID): List<HomeworkSubmission> =
        list("schoolId = ?1 and studentId = ?2", schoolId, studentId)

    fun countDone(homeworkId: UUID): Long =
        count("homeworkId = ?1 and status = ?2", homeworkId, uz.farzandim.shared.domain.SubmissionStatus.DONE)
}

/* --------------------------- Ko'nikma va tavsiyalar ------------------------ */

@ApplicationScoped
class AssessmentTaskRepository : TenantRepository<AssessmentTask>

@ApplicationScoped
class SkillAssessmentRepository : TenantRepository<SkillAssessment> {
    fun listByStudent(schoolId: UUID, studentId: UUID): List<SkillAssessment> =
        list("schoolId = ?1 and studentId = ?2 order by assessedOn desc", schoolId, studentId)
}

@ApplicationScoped
class InterestRepository : PanacheRepositoryBase<Interest, UUID> {
    fun listByStudent(studentId: UUID): List<Interest> = list("studentId = ?1 order by level desc", studentId)

    fun findByKey(studentId: UUID, key: String): Interest? =
        find("studentId = ?1 and key = ?2", studentId, key).firstResult()
}

@ApplicationScoped
class RecommendationRepository : PanacheRepositoryBase<CognitiveRecommendation, UUID> {
    fun listByStudent(studentId: UUID): List<CognitiveRecommendation> =
        list("studentId = ?1 order by createdAt desc", studentId)

    fun findScoped(id: UUID, studentIds: Collection<UUID>): CognitiveRecommendation? =
        if (studentIds.isEmpty()) null else find("id = ?1 and studentId in ?2", id, studentIds).firstResult()
}

@ApplicationScoped
class StudentPerformanceRepository : PanacheRepositoryBase<StudentPerformance, StudentPerformanceId> {
    fun listForSchool(schoolId: UUID, period: uz.farzandim.shared.domain.PerformancePeriod): List<StudentPerformance> =
        list("schoolId = ?1 and id.period = ?2", schoolId, period)

    fun listForStudents(
        studentIds: Collection<UUID>,
        period: uz.farzandim.shared.domain.PerformancePeriod,
    ): List<StudentPerformance> =
        if (studentIds.isEmpty()) emptyList()
        else list("id.studentId in ?1 and id.period = ?2", studentIds, period)
}

@ApplicationScoped
class AchievementRepository : PanacheRepositoryBase<Achievement, UUID> {
    fun listByStudent(studentId: UUID): List<Achievement> =
        list("studentId = ?1 order by achievedOn desc", studentId)
}

/* ---------------------------------- AI ------------------------------------ */

@ApplicationScoped
class AiConversationRepository : PanacheRepositoryBase<AiConversation, UUID> {
    fun listForUser(userId: UUID): List<AiConversation> =
        list("userId = ?1 and archived = false order by updatedAt desc", userId)

    fun findForUser(id: UUID, userId: UUID): AiConversation? =
        find("id = ?1 and userId = ?2", id, userId).firstResult()
}

@ApplicationScoped
class AiMessageRepository : PanacheRepositoryBase<AiMessage, UUID> {
    fun listForConversation(conversationId: UUID): List<AiMessage> =
        list("conversationId = ?1 order by createdAt", conversationId)

    fun lastMessages(conversationId: UUID, limit: Int): List<AiMessage> =
        find("conversationId = ?1 order by createdAt desc", conversationId).page(0, limit).list().reversed()
}

@ApplicationScoped
class AiJobRepository : PanacheRepositoryBase<AiJob, UUID> {
    fun findByIdempotencyKey(key: String): AiJob? = find("idempotencyKey", key).firstResult()

    fun listForSchool(schoolId: UUID, limit: Int): List<AiJob> =
        find("schoolId = ?1 order by createdAt desc", schoolId).page(0, limit).list()

    fun countByStatus(status: uz.farzandim.shared.domain.JobStatus): Long = count("status", status)
}

/* ------------------------- Xabar, bildirishnoma, fayl ---------------------- */

@ApplicationScoped
class MessageRepository : PanacheRepositoryBase<MessageEntity, UUID> {
    fun listForSchool(schoolId: UUID, limit: Int): List<MessageEntity> =
        find("schoolId = ?1 order by sentAt desc", schoolId).page(0, limit).list()
}

@ApplicationScoped
class MessageRecipientRepository : PanacheRepositoryBase<MessageRecipient, MessageRecipientId> {
    fun listForUser(userId: UUID, limit: Int): List<MessageRecipient> =
        find("id.userId = ?1", userId).page(0, limit).list()

    fun countUnread(userId: UUID): Long = count("id.userId = ?1 and readAt is null", userId)

    fun markRead(messageId: UUID, userId: UUID): Int =
        update("readAt = ?1 where id.messageId = ?2 and id.userId = ?3 and readAt is null", Instant.now(), messageId, userId)
}

@ApplicationScoped
class NotificationRepository : PanacheRepositoryBase<Notification, UUID> {
    fun listForUser(userId: UUID, limit: Int): List<Notification> =
        find("userId = ?1 order by createdAt desc", userId).page(0, limit).list()

    fun countUnread(userId: UUID): Long = count("userId = ?1 and isRead = false", userId)

    fun markRead(id: UUID, userId: UUID): Int =
        update("isRead = true where id = ?1 and userId = ?2", id, userId)

    fun markAllRead(userId: UUID): Int =
        update("isRead = true where userId = ?1 and isRead = false", userId)
}

@ApplicationScoped
class FileRepository : PanacheRepositoryBase<FileObject, UUID> {
    fun findByKey(bucket: String, objectKey: String): FileObject? =
        find("bucket = ?1 and objectKey = ?2", bucket, objectKey).firstResult()

    fun listForEntity(entityType: String, entityId: UUID): List<FileObject> =
        list("entityType = ?1 and entityId = ?2 and status = ?3", entityType, entityId, uz.farzandim.shared.domain.FileStatus.READY)

    fun listStale(before: Instant, limit: Int): List<FileObject> =
        find("status = ?1 and createdAt < ?2", uz.farzandim.shared.domain.FileStatus.PENDING, before)
            .page(0, limit).list()
}

/* ------------------------------- Platforma --------------------------------- */

@ApplicationScoped
class SubscriptionPlanRepository : PanacheRepositoryBase<SubscriptionPlan, String>

@ApplicationScoped
class SchoolSubscriptionRepository : PanacheRepositoryBase<SchoolSubscription, UUID> {
    fun listForSchool(schoolId: UUID): List<SchoolSubscription> =
        list("schoolId = ?1 order by startsOn desc", schoolId)

    fun currentFor(schoolId: UUID, today: LocalDate): SchoolSubscription? =
        find(
            "schoolId = ?1 and startsOn <= ?2 and endsOn >= ?2 and status = ?3",
            schoolId, today, uz.farzandim.shared.domain.SubscriptionStatus.ACTIVE,
        ).firstResult()
}

@ApplicationScoped
class FeatureFlagRepository : PanacheRepositoryBase<FeatureFlag, String>
