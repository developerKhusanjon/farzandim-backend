package uz.farzandim.shared.domain

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "subjects")
class Subject : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "name", nullable = false) lateinit var name: String
    @Column(name = "short_name", nullable = false) lateinit var shortName: String
    @Column(name = "color_key", nullable = false) var colorKey: String = "blue"
    @Column(name = "icon_key", nullable = false) var iconKey: String = "book"
    @Column(name = "hours_per_week", nullable = false) var hoursPerWeek: Int = 2
    @Column(name = "grade_levels", nullable = false) var gradeLevels: Array<Int> = emptyArray()
    @Column(name = "archived", nullable = false) var archived: Boolean = false
}

@Entity
@Table(name = "topics")
class Topic : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "subject_id", nullable = false) lateinit var subjectId: UUID
    @Column(name = "grade_level", nullable = false) var gradeLevel: Int = 1
    @Column(name = "order_index", nullable = false) var orderIndex: Int = 0
    @Column(name = "title", nullable = false) lateinit var title: String
    @Column(name = "description") var description: String? = null
    @Column(name = "expected_terms", nullable = false) var expectedTerms: Array<String> = emptyArray()
}

@Entity
@Table(name = "teachers")
class Teacher : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "user_id", nullable = false) lateinit var userId: UUID
    @Column(name = "employee_no") var employeeNo: String? = null
    @Column(name = "qualification") var qualification: String? = null
    @Column(name = "experience_years", nullable = false) var experienceYears: Int = 0
    @Column(name = "hired_at") var hiredAt: LocalDate? = null
    @Column(name = "bio") var bio: String? = null
}

@Entity
@Table(name = "groups")
class StudyGroup : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "name", nullable = false) lateinit var name: String
    @Column(name = "grade_level", nullable = false) var gradeLevel: Int = 1
    @Column(name = "academic_year", nullable = false) lateinit var academicYear: String
    @Column(name = "class_teacher_id") var classTeacherId: UUID? = null
    @Column(name = "room") var room: String? = null
    @Column(name = "archived", nullable = false) var archived: Boolean = false
}

@Entity
@Table(name = "students")
class Student : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "user_id") var userId: UUID? = null
    @Column(name = "first_name", nullable = false) lateinit var firstName: String
    @Column(name = "last_name", nullable = false) lateinit var lastName: String
    @Column(name = "middle_name") var middleName: String? = null
    @Column(name = "avatar_url") var avatarUrl: String? = null
    @Column(name = "birth_date") var birthDate: LocalDate? = null
    @Column(name = "gender") var gender: String? = null
    @Column(name = "grade_level", nullable = false) var gradeLevel: Int = 1
    @Column(name = "group_id") var groupId: UUID? = null
    @Column(name = "enrolled_at", nullable = false) var enrolledAt: LocalDate = LocalDate.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: StudentStatus = StudentStatus.ACTIVE

    @Column(name = "search_text", insertable = false, updatable = false) var searchText: String? = null

    val fullName: String get() = "$lastName $firstName"
}

@Entity
@Table(name = "parents")
class ParentProfile : AuditedEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "user_id", nullable = false) lateinit var userId: UUID
    @Column(name = "workplace") var workplace: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_contact", nullable = false)
    var preferredContact: PreferredContact = PreferredContact.PHONE
}

@Embeddable
class StudentParentId : Serializable {
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "parent_id", nullable = false) lateinit var parentId: UUID

    override fun equals(other: Any?): Boolean =
        other is StudentParentId && other.studentId == studentId && other.parentId == parentId

    override fun hashCode(): Int = 31 * studentId.hashCode() + parentId.hashCode()
}

@Entity
@Table(name = "student_parents")
class StudentParent {
    @EmbeddedId
    var id: StudentParentId = StudentParentId()

    @Enumerated(EnumType.STRING)
    @Column(name = "relation", nullable = false)
    var relation: ParentRelation = ParentRelation.GUARDIAN

    @Column(name = "is_primary", nullable = false) var isPrimary: Boolean = false
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now()

    var studentId: UUID
        get() = id.studentId
        set(value) { id.studentId = value }

    var parentId: UUID
        get() = id.parentId
        set(value) { id.parentId = value }
}

@Entity
@Table(name = "teacher_assignments")
class TeacherAssignment : BaseEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "teacher_id", nullable = false) lateinit var teacherId: UUID
    @Column(name = "group_id", nullable = false) lateinit var groupId: UUID
    @Column(name = "subject_id", nullable = false) lateinit var subjectId: UUID
}

@Entity
@Table(name = "schedule_slots")
class ScheduleSlot : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "group_id", nullable = false) lateinit var groupId: UUID
    @Column(name = "subject_id", nullable = false) lateinit var subjectId: UUID
    @Column(name = "teacher_id", nullable = false) lateinit var teacherId: UUID
    @Column(name = "day_of_week", nullable = false) var dayOfWeek: Int = 1
    @Convert(converter = LocalTimeTextConverter::class)
    @Column(name = "start_time", nullable = false) lateinit var startTime: LocalTime
    @Convert(converter = LocalTimeTextConverter::class)
    @Column(name = "end_time", nullable = false) lateinit var endTime: LocalTime
    @Column(name = "room") var room: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "week_type", nullable = false)
    var weekType: WeekType = WeekType.ALL

    @Column(name = "active_from", nullable = false) var activeFrom: LocalDate = LocalDate.now()
    @Column(name = "active_to") var activeTo: LocalDate? = null
}

@Entity
@Table(name = "lessons")
class Lesson : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "schedule_slot_id") var scheduleSlotId: UUID? = null
    @Column(name = "group_id", nullable = false) lateinit var groupId: UUID
    @Column(name = "subject_id", nullable = false) lateinit var subjectId: UUID
    @Column(name = "teacher_id", nullable = false) lateinit var teacherId: UUID
    @Column(name = "topic_id") var topicId: UUID? = null
    @Column(name = "lesson_date", nullable = false) lateinit var lessonDate: LocalDate
    @Convert(converter = LocalTimeTextConverter::class)
    @Column(name = "start_time", nullable = false) lateinit var startTime: LocalTime
    @Convert(converter = LocalTimeTextConverter::class)
    @Column(name = "end_time", nullable = false) lateinit var endTime: LocalTime
    @Column(name = "room") var room: String? = null
    @Column(name = "title", nullable = false) var title: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: LessonStatus = LessonStatus.PLANNED

    @Column(name = "teacher_notes") var teacherNotes: String? = null
    @Column(name = "summary") var summary: String? = null

    /** jsonb: `[{ "term": "...", "definition": "..." }]` */
    @Column(name = "terms", nullable = false) var terms: String = "[]"

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_status", nullable = false)
    var summaryStatus: SummaryStatus = SummaryStatus.NONE

    @Column(name = "summary_sent_at") var summarySentAt: Instant? = null

    /** jsonb: `[{ "fileId": "...", "name": "...", "url": "..." }]` */
    @Column(name = "materials", nullable = false) var materials: String = "[]"

    @Column(name = "started_at") var startedAt: Instant? = null
    @Column(name = "finished_at") var finishedAt: Instant? = null
}
