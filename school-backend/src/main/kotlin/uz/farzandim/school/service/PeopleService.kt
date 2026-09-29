package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.CredentialsDto
import uz.farzandim.school.dto.ParentInput
import uz.farzandim.school.dto.StudentBody
import uz.farzandim.school.dto.StudentCreatedDto
import uz.farzandim.school.dto.StudentDto
import uz.farzandim.school.dto.StudentParentLinkDto
import uz.farzandim.school.dto.TeacherBody
import uz.farzandim.school.dto.TeacherDto
import uz.farzandim.shared.analytics.PerformanceService
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.Page
import uz.farzandim.shared.api.PageRequest
import uz.farzandim.shared.domain.ParentProfile
import uz.farzandim.shared.domain.Role
import uz.farzandim.shared.domain.Student
import uz.farzandim.shared.domain.StudentParent
import uz.farzandim.shared.domain.StudentStatus
import uz.farzandim.shared.domain.Teacher
import uz.farzandim.shared.domain.TeacherAssignment
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.domain.UserStatus
import uz.farzandim.shared.notify.CredentialsNotifier
import uz.farzandim.shared.platform.PlanAccess
import uz.farzandim.shared.repo.GroupRepository
import uz.farzandim.shared.repo.ParentRepository
import uz.farzandim.shared.repo.StudentParentRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.repo.TeacherAssignmentRepository
import uz.farzandim.shared.repo.TeacherRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.Passwords
import uz.farzandim.shared.util.Credentials
import java.util.UUID

/**
 * Odamlar: o'qituvchi, o'quvchi, ota-ona.
 *
 * Qidiruv `pg_trgm` indeksi ustida ishlaydi (`V5__search_indexes.sql`):
 * `lower(search_text) like '%ali%'` — B-tree bunga yordam bermaydi,
 * GIN + gin_trgm_ops esa bemalol. Bu — ODDIY qidiruv; AI/semantik
 * qidiruv alohida yo'lda (`EmbeddingService`) ketadi.
 */
@ApplicationScoped
class PeopleService {

    @Inject lateinit var students: StudentRepository
    @Inject lateinit var teachers: TeacherRepository
    @Inject lateinit var parents: ParentRepository
    @Inject lateinit var studentParents: StudentParentRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var assignments: TeacherAssignmentRepository
    @Inject lateinit var performance: PerformanceService
    @Inject lateinit var access: AccessControl
    @Inject lateinit var planAccess: PlanAccess
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var mappers: Mappers
    @Inject lateinit var credentialsNotifier: CredentialsNotifier
    @Inject lateinit var em: EntityManager

    /* ------------------------------ O'quvchilar ----------------------------- */

    fun listStudents(
        schoolId: UUID,
        q: String?,
        groupId: UUID?,
        gradeLevel: Int?,
        status: StudentStatus?,
        needsAttention: Boolean?,
        page: PageRequest,
    ): Page<StudentDto> {
        lookups.load(schoolId)

        val visible = access.visibleGroupIds()
        if (visible != null && visible.isEmpty()) return Page.empty(page.page, page.size)
        if (groupId != null) access.assertCanTouchGroup(groupId)

        val conditions = mutableListOf("s.school_id = :school")
        val params = mutableMapOf<String, Any>("school" to schoolId)

        if (!q.isNullOrBlank()) {
            conditions += "s.search_text like :pattern"
            params["pattern"] = "%${q.trim().lowercase()}%"
        }
        if (groupId != null) {
            conditions += "s.group_id = :group"
            params["group"] = groupId
        } else if (visible != null) {
            conditions += "s.group_id in (:visible)"
            params["visible"] = visible
        }
        if (gradeLevel != null) {
            conditions += "s.grade_level = :grade"
            params["grade"] = gradeLevel
        }
        conditions += "s.status = :status"
        params["status"] = (status ?: StudentStatus.ACTIVE).name

        val where = conditions.joinToString(" and ")
        val sortColumn = page.orderBy(
            mapOf(
                "lastName" to "s.last_name",
                "firstName" to "s.first_name",
                "gradeLevel" to "s.grade_level",
                "enrolledAt" to "s.enrolled_at",
            ),
            "s.last_name",
        )

        val countQuery = em.createNativeQuery("select count(*) from students s where $where")
        params.forEach { (k, v) -> countQuery.setParameter(k, v) }
        val total = (countQuery.singleResult as Number).toLong()
        if (total == 0L) return Page.empty(page.page, page.size)

        val idQuery = em.createNativeQuery(
            "select s.id from students s where $where order by $sortColumn limit :limit offset :offset",
        )
        params.forEach { (k, v) -> idQuery.setParameter(k, v) }
        idQuery.setParameter("limit", page.size)
        idQuery.setParameter("offset", page.offset)

        @Suppress("UNCHECKED_CAST")
        val ids = idQuery.resultList as List<UUID>
        val items = toStudentDtos(schoolId, students.findAllByIds(schoolId, ids))
        // Native so'rovdagi tartibni saqlaymiz.
        val ordered = ids.mapNotNull { id -> items.firstOrNull { it.id == id } }

        val filtered = if (needsAttention == true) ordered.filter { it.needsAttention } else ordered
        return Page(filtered, page.page, page.size, total)
    }

    fun getStudent(schoolId: UUID, id: UUID): StudentDto {
        lookups.load(schoolId)
        val student = students.requireInSchool(id, schoolId, "O'quvchi")
        student.groupId?.let { access.assertCanTouchGroup(it) }
        return toStudentDtos(schoolId, listOf(student)).first()
    }

    fun toStudentDtos(schoolId: UUID, list: List<Student>): List<StudentDto> {
        if (list.isEmpty()) return emptyList()
        lookups.load(schoolId)
        val ids = list.map { it.id }
        val scores = performance.academicScoresFor(schoolId, ids)
        val attendance = performance.attendanceRatesFor(schoolId, ids)
        val links = studentParents.listByStudents(ids).groupBy { it.studentId }
        val parentProfiles = parents.findAllByIds(links.values.flatten().map { it.parentId }.distinct())
            .associateBy { it.id }
        val parentUsers = users.findAllByIds(parentProfiles.values.map { it.userId }).associateBy { it.id }

        return list.map { student ->
            val score = scores[student.id] ?: 0
            val rate = attendance[student.id] ?: 100
            StudentDto(
                id = student.id,
                schoolId = student.schoolId,
                firstName = student.firstName,
                lastName = student.lastName,
                middleName = student.middleName,
                avatarUrl = student.avatarUrl,
                birthDate = student.birthDate,
                gender = student.gender,
                gradeLevel = student.gradeLevel,
                groupId = student.groupId,
                groupName = lookups.groupName(student.groupId),
                enrolledAt = student.enrolledAt,
                status = student.status,
                parents = links[student.id].orEmpty().mapNotNull { link ->
                    val profile = parentProfiles[link.parentId] ?: return@mapNotNull null
                    val user = parentUsers[profile.userId] ?: return@mapNotNull null
                    StudentParentLinkDto(
                        parentId = profile.id,
                        fullName = "${user.lastName} ${user.firstName}",
                        relation = link.relation,
                        phone = user.phone,
                        isPrimary = link.isPrimary,
                        hasAccount = user.passwordHash != null,
                    )
                },
                academicScore = score,
                skillScore = 0,
                knowledgeScore = score,
                attendanceRate = rate,
                scoreDelta = 0,
                needsAttention = score < PerformanceService.ATTENTION_SCORE ||
                    rate < PerformanceService.ATTENTION_ATTENDANCE,
            )
        }
    }

    @Transactional
    fun createStudent(schoolId: UUID, body: StudentBody): StudentCreatedDto {
        access.assertAdmin()
        // Tarifdagi o'quvchilar chegarasi (super admin panelidagi `maxStudents`)
        planAccess.assertStudentCapacity(schoolId)
        body.groupId?.let { groups.requireInSchool(it, schoolId, "Guruh") }

        val student = Student().apply {
            this.schoolId = schoolId
            this.firstName = body.firstName.trim()
            this.lastName = body.lastName.trim()
            this.middleName = body.middleName?.trim()
            this.birthDate = body.birthDate
            this.gender = body.gender
            this.gradeLevel = body.gradeLevel
            this.groupId = body.groupId
            body.enrolledAt?.let { this.enrolledAt = it }
        }
        students.persist(student)

        var credentials: CredentialsDto? = null
        if (body.createParentAccount) {
            val input = body.parent ?: throw ApiException.validation(
                "Ota-ona ma'lumoti kerak",
                mapOf("parent" to "to'ldiring"),
            )
            credentials = attachParent(schoolId, student, input)
        }

        lookups.load(schoolId)
        return StudentCreatedDto(toStudentDtos(schoolId, listOf(student)).first(), credentials)
    }

    /**
     * Ota-ona akkaunti. Parol bir marta — shu javobda — ko'rsatiladi,
     * keyin faqat bcrypt hash qoladi. Maktab uni chop etib beradi.
     */
    @Transactional
    fun attachParent(schoolId: UUID, student: Student, input: ParentInput): CredentialsDto {
        val existingUser = input.phone?.takeIf { it.isNotBlank() }?.let { users.findByEmailOrPhone(it) }
            ?: input.email?.takeIf { it.isNotBlank() }?.let { users.findByEmailOrPhone(it) }

        val (user, password) = if (existingUser != null) {
            existingUser to null
        } else {
            val username = Credentials.suggestUsername(input.firstName, input.lastName) {
                users.usernameTaken(it)
            }
            val rawPassword = Credentials.randomPassword()
            val created = UserAccount().apply {
                this.schoolId = schoolId
                this.role = Role.PARENT
                this.firstName = input.firstName.trim()
                this.lastName = input.lastName.trim()
                this.username = username
                this.phone = input.phone?.takeIf { it.isNotBlank() }
                this.email = input.email?.takeIf { it.isNotBlank() }?.lowercase()
                this.passwordHash = Passwords.hash(rawPassword)
                this.status = UserStatus.INVITED
                this.mustChangePassword = true
            }
            users.persist(created)
            created to rawPassword
        }

        val profile = parents.findByUserId(user.id) ?: ParentProfile().apply {
            this.userId = user.id
            this.schoolId = schoolId
        }.also { parents.persist(it) }

        if (!studentParents.exists(profile.id, student.id)) {
            studentParents.persist(
                StudentParent().apply {
                    this.studentId = student.id
                    this.parentId = profile.id
                    this.relation = input.relation
                    this.isPrimary = input.isPrimary
                },
            )
        }

        password?.let {
            credentialsNotifier.send(user.phone, user.username.orEmpty(), it, schoolId, user.id, user.locale, user.email)
        }

        return CredentialsDto(
            username = user.username ?: user.phone ?: user.email.orEmpty(),
            password = password ?: "(mavjud hisob — parol o'zgartirilmadi)",
        )
    }

    @Transactional
    fun updateStudent(schoolId: UUID, id: UUID, body: StudentBody): StudentDto {
        access.assertAdmin()
        val student = students.requireInSchool(id, schoolId, "O'quvchi")
        student.firstName = body.firstName.trim()
        student.lastName = body.lastName.trim()
        student.middleName = body.middleName?.trim()
        student.birthDate = body.birthDate
        student.gender = body.gender
        student.gradeLevel = body.gradeLevel
        body.groupId?.let { groups.requireInSchool(it, schoolId, "Guruh") }
        student.groupId = body.groupId
        body.status?.let { student.status = it }
        student.touch()
        lookups.load(schoolId)
        return toStudentDtos(schoolId, listOf(student)).first()
    }

    @Transactional
    fun archiveStudent(schoolId: UUID, id: UUID) {
        access.assertAdmin()
        val student = students.requireInSchool(id, schoolId, "O'quvchi")
        student.status = StudentStatus.TRANSFERRED
        student.touch()
    }

    /* ------------------------------ O'qituvchilar --------------------------- */

    fun listTeachers(schoolId: UUID): List<TeacherDto> {
        lookups.load(schoolId)
        val all = teachers.listInSchool(schoolId)
        if (all.isEmpty()) return emptyList()
        val userMap = users.findAllByIds(all.map { it.userId }).associateBy { it.id }
        val assignmentMap = assignments.listInSchool(schoolId).groupBy { it.teacherId }

        return all.mapNotNull { teacher ->
            val user = userMap[teacher.userId] ?: return@mapNotNull null
            val own = assignmentMap[teacher.id].orEmpty()
            mappers.teacher(
                teacher,
                user,
                own.map { it.subjectId }.distinct(),
                own.map { it.groupId }.distinct(),
                0,
            )
        }.sortedBy { it.lastName }
    }

    fun getTeacher(schoolId: UUID, id: UUID): TeacherDto =
        listTeachers(schoolId).firstOrNull { it.id == id } ?: throw ApiException.notFound("O'qituvchi", id)

    @Transactional
    fun createTeacher(schoolId: UUID, body: TeacherBody): Pair<TeacherDto, CredentialsDto?> {
        access.assertAdmin()

        val username = Credentials.suggestUsername(body.firstName, body.lastName) {
            users.usernameTaken(it)
        }
        val rawPassword = Credentials.randomPassword()

        val user = UserAccount().apply {
            this.schoolId = schoolId
            this.role = Role.TEACHER
            this.firstName = body.firstName.trim()
            this.lastName = body.lastName.trim()
            this.middleName = body.middleName?.trim()
            this.username = username
            this.email = body.email?.takeIf { it.isNotBlank() }?.lowercase()
            this.phone = body.phone?.takeIf { it.isNotBlank() }
            this.passwordHash = if (body.createAccount) Passwords.hash(rawPassword) else null
            this.status = if (body.createAccount) UserStatus.INVITED else UserStatus.ACTIVE
            this.mustChangePassword = body.createAccount
        }
        users.persist(user)

        val teacher = Teacher().apply {
            this.schoolId = schoolId
            this.userId = user.id
            this.employeeNo = body.employeeNo
            this.qualification = body.qualification
            this.experienceYears = body.experienceYears
            this.hiredAt = body.hiredAt
            this.bio = body.bio
        }
        teachers.persist(teacher)

        if (body.createAccount) {
            credentialsNotifier.send(user.phone, username, rawPassword, schoolId, user.id, user.locale, user.email)
        }

        lookups.load(schoolId)
        val dto = mappers.teacher(teacher, user, emptyList(), emptyList(), 0)
        return dto to if (body.createAccount) CredentialsDto(username, rawPassword) else null
    }

    @Transactional
    fun resetTeacherCredentials(schoolId: UUID, teacherId: UUID): CredentialsDto {
        access.assertAdmin()
        val teacher = teachers.requireInSchool(teacherId, schoolId, "O'qituvchi")
        val user = users.findById(teacher.userId) ?: throw ApiException.notFound("Foydalanuvchi")
        val rawPassword = Credentials.randomPassword()
        user.passwordHash = Passwords.hash(rawPassword)
        user.mustChangePassword = true
        user.touch()
        credentialsNotifier.send(user.phone, user.username.orEmpty(), rawPassword, schoolId, user.id, user.locale, user.email)
        return CredentialsDto(user.username ?: user.email.orEmpty(), rawPassword)
    }

    @Transactional
    fun assignTeacher(schoolId: UUID, teacherId: UUID, groupId: UUID, subjectId: UUID) {
        access.assertAdmin()
        teachers.requireInSchool(teacherId, schoolId, "O'qituvchi")
        groups.requireInSchool(groupId, schoolId, "Guruh")
        subjects.requireInSchool(subjectId, schoolId, "Fan")
        if (assignments.teaches(teacherId, groupId, subjectId)) return
        assignments.persist(
            TeacherAssignment().apply {
                this.schoolId = schoolId
                this.teacherId = teacherId
                this.groupId = groupId
                this.subjectId = subjectId
            },
        )
    }

    @Transactional
    fun removeAssignment(schoolId: UUID, teacherId: UUID, groupId: UUID, subjectId: UUID) {
        access.assertAdmin()
        assignments.delete(
            "schoolId = ?1 and teacherId = ?2 and groupId = ?3 and subjectId = ?4",
            schoolId, teacherId, groupId, subjectId,
        )
    }
}
