package uz.farzandim.shared.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "grades")
class Grade : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "lesson_id") var lessonId: UUID? = null
    @Column(name = "subject_id", nullable = false) lateinit var subjectId: UUID
    @Column(name = "topic_id") var topicId: UUID? = null
    @Column(name = "teacher_id", nullable = false) lateinit var teacherId: UUID
    @Column(name = "value", nullable = false) var value: Double = 0.0
    @Column(name = "max_value", nullable = false) var maxValue: Double = 100.0

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    var type: GradeType = GradeType.CLASSWORK

    @Column(name = "comment") var comment: String? = null
    @Column(name = "graded_on", nullable = false) var gradedOn: LocalDate = LocalDate.now()
    @Column(name = "sent_to_parent_at") var sentToParentAt: Instant? = null

    /** 0–100 ga normallashtirilgan ball (docs/02-data-model.md, 4-bo'lim). */
    val normalized: Double get() = if (maxValue <= 0) 0.0 else (value / maxValue) * 100.0
}

@Entity
@Table(name = "attendance")
class Attendance : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "lesson_id", nullable = false) lateinit var lessonId: UUID
    @Column(name = "attended_on", nullable = false) lateinit var attendedOn: LocalDate

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: AttendanceStatus = AttendanceStatus.PRESENT

    @Column(name = "minutes_late") var minutesLate: Int? = null
    @Column(name = "reason") var reason: String? = null
    @Column(name = "marked_by") var markedBy: UUID? = null
}

@Entity
@Table(name = "homework")
class Homework : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "group_id", nullable = false) lateinit var groupId: UUID
    @Column(name = "subject_id", nullable = false) lateinit var subjectId: UUID
    @Column(name = "teacher_id", nullable = false) lateinit var teacherId: UUID
    @Column(name = "lesson_id") var lessonId: UUID? = null
    @Column(name = "topic_id") var topicId: UUID? = null
    @Column(name = "title", nullable = false) lateinit var title: String
    @Column(name = "description", nullable = false) var description: String = ""
    @Column(name = "due_date", nullable = false) lateinit var dueDate: LocalDate
    @Column(name = "estimated_minutes", nullable = false) var estimatedMinutes: Int = 20

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    var scope: HomeworkScope = HomeworkScope.GROUP

    @Column(name = "target_student_ids", nullable = false) var targetStudentIds: Array<UUID> = emptyArray()

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false)
    var difficulty: Difficulty = Difficulty.NORMAL

    @Column(name = "ai_generated", nullable = false) var aiGenerated: Boolean = false
    @Column(name = "parent_hint") var parentHint: String? = null
    @Column(name = "attachments", nullable = false) var attachments: String = "[]"
    @Column(name = "sent_to_parents_at") var sentToParentsAt: Instant? = null
}

@Entity
@Table(name = "homework_variants")
class HomeworkVariant : BaseEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "homework_id", nullable = false) lateinit var homeworkId: UUID
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "simplified_description", nullable = false) var simplifiedDescription: String = ""
    @Column(name = "parent_hint") var parentHint: String? = null
    @Column(name = "step_by_step", nullable = false) var stepByStep: Array<String> = emptyArray()
    @Column(name = "reason") var reason: String? = null
    @Column(name = "ai_generated", nullable = false) var aiGenerated: Boolean = true
    @Column(name = "approved_by") var approvedBy: UUID? = null
}

@Entity
@Table(name = "homework_submissions")
class HomeworkSubmission : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "homework_id", nullable = false) lateinit var homeworkId: UUID
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: SubmissionStatus = SubmissionStatus.PENDING

    @Column(name = "completed_at") var completedAt: Instant? = null
    @Column(name = "parent_confirmed", nullable = false) var parentConfirmed: Boolean = false
    @Column(name = "confirmed_by") var confirmedBy: UUID? = null
    @Column(name = "attachments", nullable = false) var attachments: String = "[]"
    @Column(name = "teacher_feedback") var teacherFeedback: String? = null
    @Column(name = "grade_value") var gradeValue: Double? = null
}

@Entity
@Table(name = "assessment_tasks")
class AssessmentTask : AuditedEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "teacher_id", nullable = false) lateinit var teacherId: UUID
    @Column(name = "group_id") var groupId: UUID? = null
    @Column(name = "student_ids", nullable = false) var studentIds: Array<UUID> = emptyArray()
    @Column(name = "title", nullable = false) lateinit var title: String
    @Column(name = "instruction", nullable = false) var instruction: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    var category: SkillCategory = SkillCategory.COGNITIVE

    @Column(name = "expected_signals", nullable = false) var expectedSignals: Array<String> = emptyArray()
    @Column(name = "observation_guide") var observationGuide: String? = null

    /** jsonb: `[{ "studentId": "...", "note": "..." }]` */
    @Column(name = "observations", nullable = false) var observations: String = "[]"

    @Column(name = "ai_generated", nullable = false) var aiGenerated: Boolean = false

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: AssessmentTaskStatus = AssessmentTaskStatus.DRAFT

    @Column(name = "result_summary") var resultSummary: String? = null
    @Column(name = "due_date") var dueDate: LocalDate? = null
}

@Entity
@Table(name = "skill_assessments")
class SkillAssessment : BaseEntity() {
    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "teacher_id") var teacherId: UUID? = null
    @Column(name = "task_id") var taskId: UUID? = null
    @Column(name = "assessed_on", nullable = false) var assessedOn: LocalDate = LocalDate.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    var category: SkillCategory = SkillCategory.COGNITIVE

    /** jsonb: `[{ "key": "...", "label": "...", "score": 0..100, "note": "..." }]` */
    @Column(name = "skills", nullable = false) var skills: String = "[]"

    @Column(name = "summary") var summary: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    var source: SkillSource = SkillSource.OBSERVATION
}

@Entity
@Table(name = "interests")
class Interest : BaseEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "key", nullable = false) lateinit var key: String
    @Column(name = "label", nullable = false) lateinit var label: String
    @Column(name = "description") var description: String? = null
    @Column(name = "level", nullable = false) var level: Int = 50
    @Column(name = "color_key", nullable = false) var colorKey: String = "blue"
    @Column(name = "icon_key", nullable = false) var iconKey: String = "star"

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    var source: InterestSource = InterestSource.TEACHER

    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now()
}

@Entity
@Table(name = "cognitive_recommendations")
class CognitiveRecommendation : AuditedEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "teacher_id") var teacherId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "area", nullable = false)
    var area: CognitiveArea = CognitiveArea.LOGIC

    @Column(name = "title", nullable = false) lateinit var title: String
    @Column(name = "description", nullable = false) var description: String = ""
    @Column(name = "rationale", nullable = false) var rationale: String = ""
    @Column(name = "steps", nullable = false) var steps: Array<String> = emptyArray()
    @Column(name = "duration_minutes", nullable = false) var durationMinutes: Int = 15

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false)
    var difficulty: Difficulty = Difficulty.NORMAL

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: RecommendationStatus = RecommendationStatus.SUGGESTED

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_to", nullable = false)
    var assignedTo: AssignedTo = AssignedTo.PARENT

    @Column(name = "created_by_ai", nullable = false) var createdByAi: Boolean = true
}

@Embeddable
class StudentPerformanceId : Serializable {
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false)
    lateinit var period: PerformancePeriod

    override fun equals(other: Any?): Boolean =
        other is StudentPerformanceId && other.studentId == studentId && other.period == period

    override fun hashCode(): Int = 31 * studentId.hashCode() + period.hashCode()
}

@Entity
@Table(name = "student_performance")
class StudentPerformance {
    @EmbeddedId
    var id: StudentPerformanceId = StudentPerformanceId()

    @Column(name = "school_id", nullable = false) lateinit var schoolId: UUID
    @Column(name = "academic_score", nullable = false) var academicScore: Double = 0.0
    @Column(name = "skill_score", nullable = false) var skillScore: Double = 0.0
    @Column(name = "knowledge_score", nullable = false) var knowledgeScore: Double = 0.0
    @Column(name = "attendance_rate", nullable = false) var attendanceRate: Double = 0.0
    @Column(name = "score_delta", nullable = false) var scoreDelta: Double = 0.0
    @Column(name = "by_subject", nullable = false) var bySubject: String = "[]"
    @Column(name = "by_topic", nullable = false) var byTopic: String = "[]"
    @Column(name = "strengths", nullable = false) var strengths: Array<String> = emptyArray()
    @Column(name = "developing", nullable = false) var developing: Array<String> = emptyArray()
    @Column(name = "needs_attention", nullable = false) var needsAttention: Array<String> = emptyArray()
    @Column(name = "trend", nullable = false) var trend: String = "[]"
    @Column(name = "computed_at", nullable = false) var computedAt: Instant = Instant.now()
}

@Entity
@Table(name = "achievements")
class Achievement : BaseEntity() {
    @Column(name = "school_id") var schoolId: UUID? = null
    @Column(name = "student_id", nullable = false) lateinit var studentId: UUID
    @Column(name = "title", nullable = false) lateinit var title: String
    @Column(name = "description") var description: String? = null
    @Column(name = "achieved_on", nullable = false) var achievedOn: LocalDate = LocalDate.now()
    @Column(name = "icon_key", nullable = false) var iconKey: String = "trophy"
    @Column(name = "color_key", nullable = false) var colorKey: String = "amber"
}
