package uz.farzandim.school.service

import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import uz.farzandim.school.dto.AchievementDto
import uz.farzandim.school.dto.AssessmentTaskDto
import uz.farzandim.school.dto.AttendanceDto
import uz.farzandim.school.dto.GradeDto
import uz.farzandim.school.dto.HomeworkDto
import uz.farzandim.school.dto.HomeworkSubmissionDto
import uz.farzandim.school.dto.HomeworkVariantDto
import uz.farzandim.school.dto.InterestDto
import uz.farzandim.school.dto.LessonDto
import uz.farzandim.school.dto.LessonTermDto
import uz.farzandim.school.dto.NotificationDto
import uz.farzandim.school.dto.RecommendationDto
import uz.farzandim.school.dto.ScheduleSlotDto
import uz.farzandim.school.dto.SchoolDto
import uz.farzandim.school.dto.SchoolSettingsDto
import uz.farzandim.school.dto.SkillAssessmentDto
import uz.farzandim.school.dto.SkillScoreDto
import uz.farzandim.school.dto.SubjectDto
import uz.farzandim.school.dto.TeacherDto
import uz.farzandim.school.dto.TopicDto
import uz.farzandim.shared.domain.Achievement
import uz.farzandim.shared.domain.AssessmentTask
import uz.farzandim.shared.domain.Attendance
import uz.farzandim.shared.domain.CognitiveRecommendation
import uz.farzandim.shared.domain.Grade
import uz.farzandim.shared.domain.Homework
import uz.farzandim.shared.domain.HomeworkSubmission
import uz.farzandim.shared.domain.HomeworkVariant
import uz.farzandim.shared.domain.Interest
import uz.farzandim.shared.domain.Lesson
import uz.farzandim.shared.domain.Notification
import uz.farzandim.shared.domain.ScheduleSlot
import uz.farzandim.shared.domain.School
import uz.farzandim.shared.domain.SkillAssessment
import uz.farzandim.shared.domain.Subject
import uz.farzandim.shared.domain.Teacher
import uz.farzandim.shared.domain.Topic
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.platform.PlanAccess
import uz.farzandim.shared.util.Json

@RequestScoped
class Mappers {

    @Inject lateinit var lookups: Lookups
    @Inject lateinit var planAccess: PlanAccess

    fun school(entity: School, studentCount: Long, teacherCount: Long, groupCount: Long) = SchoolDto(
        id = entity.id,
        name = entity.name,
        shortName = entity.shortName,
        logoUrl = entity.logoUrl,
        address = entity.address,
        phone = entity.phone,
        email = entity.email,
        academicYear = entity.academicYear,
        gradingSystem = entity.gradingSystem,
        locale = entity.locale,
        studentCount = studentCount,
        teacherCount = teacherCount,
        groupCount = groupCount,
        subscriptionPlan = entity.subscriptionPlan.name,
        subscriptionUntil = entity.subscriptionUntil,
        settings = Json.read(entity.settings, SchoolSettingsDto()),
        planFeatures = planAccess.features(entity),
        aiDailyLimit = entity.aiDailyLimit,
    )

    fun subject(entity: Subject) = SubjectDto(
        id = entity.id,
        schoolId = entity.schoolId,
        name = entity.name,
        shortName = entity.shortName,
        colorKey = entity.colorKey,
        iconKey = entity.iconKey,
        hoursPerWeek = entity.hoursPerWeek,
        gradeLevels = entity.gradeLevels.toList(),
    )

    fun topic(entity: Topic) = TopicDto(
        id = entity.id,
        subjectId = entity.subjectId,
        gradeLevel = entity.gradeLevel,
        orderIndex = entity.orderIndex,
        title = entity.title,
        description = entity.description,
        expectedTerms = entity.expectedTerms.toList(),
    )

    fun teacher(
        entity: Teacher,
        user: UserAccount,
        subjectIds: List<java.util.UUID>,
        groupIds: List<java.util.UUID>,
        averageGroupScore: Int,
    ) = TeacherDto(
        id = entity.id,
        userId = entity.userId,
        schoolId = entity.schoolId,
        firstName = user.firstName,
        lastName = user.lastName,
        middleName = user.middleName,
        avatarUrl = user.avatarUrl,
        username = user.username,
        email = user.email,
        phone = user.phone,
        employeeNo = entity.employeeNo,
        subjectIds = subjectIds,
        groupIds = groupIds,
        qualification = entity.qualification,
        experienceYears = entity.experienceYears,
        hiredAt = entity.hiredAt,
        status = user.status,
        averageGroupScore = averageGroupScore,
    )

    fun scheduleSlot(entity: ScheduleSlot) = ScheduleSlotDto(
        id = entity.id,
        schoolId = entity.schoolId,
        groupId = entity.groupId,
        groupName = lookups.groupName(entity.groupId),
        subjectId = entity.subjectId,
        subjectName = lookups.subjectName(entity.subjectId),
        colorKey = lookups.colorKey(entity.subjectId),
        teacherId = entity.teacherId,
        teacherName = lookups.teacherName(entity.teacherId),
        dayOfWeek = entity.dayOfWeek,
        startTime = entity.startTime,
        endTime = entity.endTime,
        room = entity.room,
    )

    fun lesson(entity: Lesson) = LessonDto(
        id = entity.id,
        schoolId = entity.schoolId,
        scheduleSlotId = entity.scheduleSlotId,
        groupId = entity.groupId,
        groupName = lookups.groupName(entity.groupId),
        subjectId = entity.subjectId,
        subjectName = lookups.subjectName(entity.subjectId),
        colorKey = lookups.colorKey(entity.subjectId),
        teacherId = entity.teacherId,
        teacherName = lookups.teacherName(entity.teacherId),
        date = entity.lessonDate,
        startTime = entity.startTime,
        endTime = entity.endTime,
        room = entity.room,
        topicId = entity.topicId,
        title = entity.title,
        status = entity.status,
        summary = entity.summary,
        terms = terms(entity.terms),
        summaryStatus = entity.summaryStatus,
    )

    fun terms(raw: String?): List<LessonTermDto> = Json.read(raw, emptyList())

    fun grade(entity: Grade) = GradeDto(
        id = entity.id,
        schoolId = entity.schoolId,
        studentId = entity.studentId,
        studentName = lookups.studentName(entity.studentId),
        lessonId = entity.lessonId,
        subjectId = entity.subjectId,
        subjectName = lookups.subjectName(entity.subjectId),
        colorKey = lookups.colorKey(entity.subjectId),
        teacherId = entity.teacherId,
        value = entity.value,
        maxValue = entity.maxValue,
        type = entity.type,
        topicId = entity.topicId,
        topicTitle = lookups.topicTitle(entity.topicId),
        comment = entity.comment,
        date = entity.gradedOn,
        sentToParentAt = entity.sentToParentAt,
    )

    fun attendance(entity: Attendance, subjectId: java.util.UUID?) = AttendanceDto(
        id = entity.id,
        schoolId = entity.schoolId,
        studentId = entity.studentId,
        studentName = lookups.studentName(entity.studentId),
        lessonId = entity.lessonId,
        subjectId = subjectId ?: entity.lessonId,
        date = entity.attendedOn,
        status = entity.status,
        minutesLate = entity.minutesLate,
        reason = entity.reason,
    )

    fun homework(entity: Homework, totalStudents: Int, doneCount: Int, variantCount: Int) = HomeworkDto(
        id = entity.id,
        schoolId = entity.schoolId,
        groupId = entity.groupId,
        groupName = lookups.groupName(entity.groupId),
        subjectId = entity.subjectId,
        subjectName = lookups.subjectName(entity.subjectId),
        colorKey = lookups.colorKey(entity.subjectId),
        teacherId = entity.teacherId,
        teacherName = lookups.teacherName(entity.teacherId),
        lessonId = entity.lessonId,
        topicId = entity.topicId,
        title = entity.title,
        description = entity.description,
        dueDate = entity.dueDate,
        estimatedMinutes = entity.estimatedMinutes,
        scope = entity.scope,
        targetStudentIds = entity.targetStudentIds.toList(),
        aiGenerated = entity.aiGenerated,
        parentHint = entity.parentHint,
        difficulty = entity.difficulty,
        createdAt = entity.createdAt,
        sentToParentsAt = entity.sentToParentsAt,
        totalStudents = totalStudents,
        doneCount = doneCount,
        variantCount = variantCount,
    )

    fun variant(entity: HomeworkVariant) = HomeworkVariantDto(
        id = entity.id,
        homeworkId = entity.homeworkId,
        studentId = entity.studentId,
        studentName = lookups.studentName(entity.studentId),
        simplifiedDescription = entity.simplifiedDescription,
        parentHint = entity.parentHint,
        stepByStep = entity.stepByStep.toList(),
        reason = entity.reason,
        aiGenerated = entity.aiGenerated,
        createdAt = entity.createdAt,
    )

    fun submission(entity: HomeworkSubmission, avatarUrl: String? = null) = HomeworkSubmissionDto(
        id = entity.id,
        homeworkId = entity.homeworkId,
        studentId = entity.studentId,
        studentName = lookups.studentName(entity.studentId),
        avatarUrl = avatarUrl,
        status = entity.status,
        completedAt = entity.completedAt,
        parentConfirmed = entity.parentConfirmed,
        teacherFeedback = entity.teacherFeedback,
        grade = entity.gradeValue,
    )

    fun assessmentTask(entity: AssessmentTask) = AssessmentTaskDto(
        id = entity.id,
        schoolId = entity.schoolId,
        teacherId = entity.teacherId,
        teacherName = lookups.teacherName(entity.teacherId),
        groupId = entity.groupId,
        groupName = entity.groupId?.let { lookups.groupName(it) },
        studentIds = entity.studentIds.toList(),
        title = entity.title,
        instruction = entity.instruction,
        category = entity.category,
        expectedSignals = entity.expectedSignals.toList(),
        observationGuide = entity.observationGuide,
        aiGenerated = entity.aiGenerated,
        status = entity.status,
        resultSummary = entity.resultSummary,
        createdAt = entity.createdAt,
        dueDate = entity.dueDate,
    )

    fun skillAssessment(entity: SkillAssessment) = SkillAssessmentDto(
        id = entity.id,
        studentId = entity.studentId,
        teacherId = entity.teacherId,
        teacherName = lookups.teacherName(entity.teacherId),
        date = entity.assessedOn,
        category = entity.category,
        skills = Json.read(entity.skills, emptyList<SkillScoreDto>()),
        summary = entity.summary,
        source = entity.source.name,
        taskId = entity.taskId,
    )

    fun interest(entity: Interest) = InterestDto(
        id = entity.id,
        studentId = entity.studentId,
        key = entity.key,
        label = entity.label,
        description = entity.description,
        level = entity.level,
        colorKey = entity.colorKey,
        iconKey = entity.iconKey,
        source = entity.source.name,
        updatedAt = entity.updatedAt,
    )

    fun recommendation(entity: CognitiveRecommendation) = RecommendationDto(
        id = entity.id,
        schoolId = entity.schoolId,
        studentId = entity.studentId,
        studentName = lookups.studentName(entity.studentId),
        createdByAi = entity.createdByAi,
        teacherId = entity.teacherId,
        area = entity.area,
        title = entity.title,
        description = entity.description,
        rationale = entity.rationale,
        steps = entity.steps.toList(),
        durationMinutes = entity.durationMinutes,
        difficulty = entity.difficulty,
        status = entity.status,
        assignedTo = entity.assignedTo.name,
        createdAt = entity.createdAt,
    )

    fun achievement(entity: Achievement) = AchievementDto(
        id = entity.id,
        studentId = entity.studentId,
        title = entity.title,
        description = entity.description,
        date = entity.achievedOn,
        iconKey = entity.iconKey,
        colorKey = entity.colorKey,
    )

    fun notification(entity: Notification) = NotificationDto(
        id = entity.id,
        userId = entity.userId,
        type = entity.type,
        title = entity.title,
        body = entity.body,
        colorKey = entity.colorKey,
        isRead = entity.isRead,
        createdAt = entity.createdAt,
        link = entity.link,
    )
}
