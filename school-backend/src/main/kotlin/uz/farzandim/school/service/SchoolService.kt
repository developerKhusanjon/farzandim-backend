package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.GroupBody
import uz.farzandim.school.dto.GroupDto
import uz.farzandim.school.dto.SchoolDto
import uz.farzandim.school.dto.SchoolStatsDto
import uz.farzandim.school.dto.StatValue
import uz.farzandim.school.dto.SubjectBody
import uz.farzandim.school.dto.SubjectDto
import uz.farzandim.school.dto.TopicBody
import uz.farzandim.school.dto.TopicDto
import uz.farzandim.school.dto.UpdateSchoolBody
import uz.farzandim.shared.analytics.PerformanceService
import uz.farzandim.shared.analytics.SubjectScoreDto
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.cache.CacheKeys
import uz.farzandim.shared.cache.ValkeyService
import uz.farzandim.shared.cache.cachedAs
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.StudyGroup
import uz.farzandim.shared.domain.Subject
import uz.farzandim.shared.domain.Topic
import uz.farzandim.shared.repo.GroupRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.repo.TeacherAssignmentRepository
import uz.farzandim.shared.repo.TeacherRepository
import uz.farzandim.shared.repo.TopicRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.util.Json
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

@ApplicationScoped
class SchoolService {

    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var topics: TopicRepository
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var teachers: TeacherRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assignments: TeacherAssignmentRepository
    @Inject lateinit var performance: PerformanceService
    @Inject lateinit var valkey: ValkeyService
    @Inject lateinit var access: AccessControl
    @Inject lateinit var mappers: Mappers
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var em: EntityManager

    /* --------------------------------- Maktab ------------------------------- */

    fun get(schoolId: UUID): SchoolDto {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        return mappers.school(
            school,
            students.countActive(schoolId),
            teachers.countInSchool(schoolId),
            groups.countInSchool(schoolId),
        )
    }

    @Transactional
    fun update(schoolId: UUID, body: UpdateSchoolBody): SchoolDto {
        access.assertAdmin()
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        body.name?.let { school.name = it }
        body.address?.let { school.address = it }
        body.phone?.let { school.phone = it }
        body.email?.let { school.email = it }
        body.academicYear?.let { school.academicYear = it }
        body.gradingSystem?.let { school.gradingSystem = it }
        body.locale?.let { school.locale = it }
        body.settings?.let { school.settings = Json.write(it) }
        school.touch()
        valkey.invalidatePrefix(CacheKeys.schoolPrefix(schoolId))
        return get(schoolId)
    }

    /**
     * Dashboard kartalari. Kesh 5 daqiqa — bu raqamlar sekundiga
     * o'zgarmaydi, lekin har bir sahifa ochilishida to'rtta agregat
     * so'rov yuborish bekor.
     */
    fun stats(schoolId: UUID): SchoolStatsDto = valkey.cachedAs(
        CacheKeys.schoolStats(schoolId),
        Duration.ofMinutes(5),
    ) {
        val studentCount = students.countActive(schoolId)
        val teacherCount = teachers.countInSchool(schoolId)
        val groupCount = groups.countInSchool(schoolId)
        val attendanceToday = attendanceRateToday(schoolId)
        val attention = performance.needsAttentionCount(schoolId)

        SchoolStatsDto(
            students = StatValue(studentCount, newStudentsThisMonth(schoolId)),
            teachers = StatValue(teacherCount, 0),
            groups = StatValue(groupCount, 0),
            attendanceToday = StatValue(attendanceToday, 0),
            needsAttention = StatValue(attention, 0),
        )
    }

    private fun attendanceRateToday(schoolId: UUID): Long {
        val result = em.createNativeQuery(
            """
            select case when count(*) = 0 then 100
                        else round(100.0 * count(*) filter (where status in ('PRESENT', 'EXCUSED')) / count(*))
                   end
            from attendance where school_id = :school and attended_on = current_date
            """.trimIndent(),
        ).setParameter("school", schoolId).singleResult
        return (result as? Number)?.toLong() ?: 100
    }

    private fun newStudentsThisMonth(schoolId: UUID): Long {
        val result = em.createNativeQuery(
            "select count(*) from students where school_id = :school and enrolled_at >= date_trunc('month', current_date)",
        ).setParameter("school", schoolId).singleResult
        return (result as? Number)?.toLong() ?: 0
    }

    /** Fanlar bo'yicha o'rtacha: score — oxirgi 30 kun, delta — oldingi 30 kunga nisbatan. */
    fun subjectAverages(schoolId: UUID): List<SubjectScoreDto> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select s.id, s.name, s.short_name, s.color_key,
                   coalesce(avg(g.value / g.max_value * 100)
                            filter (where g.graded_on >= current_date - interval '30 days'), 0) as current,
                   avg(g.value / g.max_value * 100)
                            filter (where g.graded_on < current_date - interval '30 days') as previous
            from subjects s
            left join grades g on g.subject_id = s.id
                 and g.graded_on >= current_date - interval '60 days'
            where s.school_id = :school and not s.archived
            group by s.id, s.name, s.short_name, s.color_key
            order by 5 desc
            """.trimIndent(),
        ).setParameter("school", schoolId).resultList as List<Array<Any?>>

        return rows.map {
            val current = (it[4] as Number).toDouble()
            val previous = (it[5] as Number?)?.toDouble()
            SubjectScoreDto(
                subjectId = it[0] as UUID,
                subjectName = it[1] as String,
                shortName = it[2] as String,
                colorKey = it[3] as String,
                score = current.roundToInt(),
                delta = if (previous == null) 0 else (current - previous).roundToInt(),
            )
        }
    }

    /* ---------------------------------- Fanlar ------------------------------ */

    fun listSubjects(schoolId: UUID): List<SubjectDto> =
        subjects.listActive(schoolId).map { mappers.subject(it) }

    @Transactional
    fun createSubject(schoolId: UUID, body: SubjectBody): SubjectDto {
        access.assertAdmin()
        val entity = Subject().apply {
            this.schoolId = schoolId
            this.name = body.name.trim()
            this.shortName = body.shortName.trim()
            this.colorKey = body.colorKey
            this.iconKey = body.iconKey
            this.hoursPerWeek = body.hoursPerWeek
            this.gradeLevels = body.gradeLevels.toTypedArray()
        }
        subjects.persist(entity)
        return mappers.subject(entity)
    }

    @Transactional
    fun updateSubject(schoolId: UUID, id: UUID, body: SubjectBody): SubjectDto {
        access.assertAdmin()
        val entity = subjects.requireInSchool(id, schoolId, "Fan")
        entity.name = body.name.trim()
        entity.shortName = body.shortName.trim()
        entity.colorKey = body.colorKey
        entity.iconKey = body.iconKey
        entity.hoursPerWeek = body.hoursPerWeek
        entity.gradeLevels = body.gradeLevels.toTypedArray()
        entity.touch()
        return mappers.subject(entity)
    }

    @Transactional
    fun archiveSubject(schoolId: UUID, id: UUID) {
        access.assertAdmin()
        val entity = subjects.requireInSchool(id, schoolId, "Fan")
        entity.archived = true
        entity.touch()
    }

    fun listTopics(schoolId: UUID, subjectId: UUID, gradeLevel: Int?): List<TopicDto> {
        subjects.requireInSchool(subjectId, schoolId, "Fan")
        return topics.listBySubject(schoolId, subjectId, gradeLevel).map { mappers.topic(it) }
    }

    @Transactional
    fun createTopic(schoolId: UUID, subjectId: UUID, body: TopicBody): TopicDto {
        access.assertAdmin()
        subjects.requireInSchool(subjectId, schoolId, "Fan")
        val entity = Topic().apply {
            this.schoolId = schoolId
            this.subjectId = subjectId
            this.gradeLevel = body.gradeLevel
            this.orderIndex = body.orderIndex
            this.title = body.title.trim()
            this.description = body.description
            this.expectedTerms = body.expectedTerms.toTypedArray()
        }
        topics.persist(entity)
        return mappers.topic(entity)
    }

    /* --------------------------------- Guruhlar ----------------------------- */

    fun listGroups(schoolId: UUID): List<GroupDto> {
        lookups.load(schoolId)
        val visible = access.visibleGroupIds()
        val all = groups.listActive(schoolId).filter { visible == null || it.id in visible }
        if (all.isEmpty()) return emptyList()

        val counts = studentCountsByGroup(schoolId)
        val scores = groupAverages(schoolId)
        val attendance = groupAttendance(schoolId)
        val subjectsByGroup = assignmentsByGroup(schoolId)

        return all.map { group ->
            GroupDto(
                id = group.id,
                schoolId = group.schoolId,
                name = group.name,
                gradeLevel = group.gradeLevel,
                academicYear = group.academicYear,
                classTeacherId = group.classTeacherId,
                classTeacherName = lookups.teacherName(group.classTeacherId),
                room = group.room,
                studentCount = counts[group.id] ?: 0,
                subjectIds = subjectsByGroup[group.id].orEmpty(),
                averageScore = scores[group.id] ?: 0,
                attendanceRate = attendance[group.id] ?: 100,
            )
        }
    }

    fun getGroup(schoolId: UUID, id: UUID): GroupDto =
        listGroups(schoolId).firstOrNull { it.id == id } ?: throw ApiException.notFound("Guruh", id)

    @Transactional
    fun createGroup(schoolId: UUID, body: GroupBody): GroupDto {
        access.assertAdmin()
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        val year = body.academicYear ?: school.academicYear
        groups.findByName(schoolId, year, body.name)?.let {
            throw ApiException.conflict("«${body.name}» guruhi $year o'quv yilida allaqachon mavjud")
        }
        val entity = StudyGroup().apply {
            this.schoolId = schoolId
            this.name = body.name.trim()
            this.gradeLevel = body.gradeLevel
            this.academicYear = year
            this.classTeacherId = body.classTeacherId
            this.room = body.room
        }
        groups.persist(entity)
        lookups.load(schoolId)
        return getGroup(schoolId, entity.id)
    }

    @Transactional
    fun updateGroup(schoolId: UUID, id: UUID, body: GroupBody): GroupDto {
        access.assertAdmin()
        val entity = groups.requireInSchool(id, schoolId, "Guruh")
        entity.name = body.name.trim()
        entity.gradeLevel = body.gradeLevel
        body.academicYear?.let { entity.academicYear = it }
        entity.classTeacherId = body.classTeacherId
        entity.room = body.room
        entity.touch()
        return getGroup(schoolId, id)
    }

    @Transactional
    fun archiveGroup(schoolId: UUID, id: UUID) {
        access.assertAdmin()
        val entity = groups.requireInSchool(id, schoolId, "Guruh")
        entity.archived = true
        entity.touch()
    }

    private fun studentCountsByGroup(schoolId: UUID): Map<UUID, Long> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            "select group_id, count(*) from students where school_id = :school and status = 'ACTIVE' and group_id is not null group by group_id",
        ).setParameter("school", schoolId).resultList as List<Array<Any?>>
        return rows.associate { (it[0] as UUID) to (it[1] as Number).toLong() }
    }

    private fun groupAverages(schoolId: UUID): Map<UUID, Int> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select st.group_id, avg(g.value / g.max_value * 100)
            from grades g join students st on st.id = g.student_id
            where g.school_id = :school and g.graded_on >= current_date - interval '30 days'
              and st.group_id is not null
            group by st.group_id
            """.trimIndent(),
        ).setParameter("school", schoolId).resultList as List<Array<Any?>>
        return rows.associate { (it[0] as UUID) to ((it[1] as Number).toDouble()).roundToInt() }
    }

    private fun groupAttendance(schoolId: UUID): Map<UUID, Int> {
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            select l.group_id,
                   100.0 * count(*) filter (where a.status in ('PRESENT', 'EXCUSED')) / nullif(count(*), 0)
            from attendance a join lessons l on l.id = a.lesson_id
            where a.school_id = :school and a.attended_on >= current_date - interval '30 days'
            group by l.group_id
            """.trimIndent(),
        ).setParameter("school", schoolId).resultList as List<Array<Any?>>
        return rows.associate { (it[0] as UUID) to ((it[1] as? Number)?.toDouble() ?: 100.0).roundToInt() }
    }

    private fun assignmentsByGroup(schoolId: UUID): Map<UUID, List<UUID>> =
        assignments.listInSchool(schoolId).groupBy({ it.groupId }, { it.subjectId })
            .mapValues { (_, ids) -> ids.distinct() }
}
