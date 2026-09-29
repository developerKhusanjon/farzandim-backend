package uz.farzandim.school.service

import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import uz.farzandim.shared.domain.StudyGroup
import uz.farzandim.shared.domain.Subject
import uz.farzandim.shared.domain.Teacher
import uz.farzandim.shared.domain.UserAccount
import uz.farzandim.shared.repo.GroupRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.SubjectRepository
import uz.farzandim.shared.repo.TeacherRepository
import uz.farzandim.shared.repo.TopicRepository
import uz.farzandim.shared.repo.UserRepository
import java.util.UUID

/**
 * Nom va rang kabi ma'lumotnoma qiymatlari.
 *
 * Bir so'rov ichida bir marta yuklanadi: 200 ta baho qaytarilganda
 * har biri uchun fan nomini alohida so'rash — klassik N+1.
 * Maktabda fan 20 ta, guruh 30 ta, o'qituvchi 50 ta — hammasi bitta
 * so'rovda keladi va so'rov oxirigacha xotirada qoladi.
 */
@RequestScoped
class Lookups {

    @Inject lateinit var subjects: SubjectRepository
    @Inject lateinit var groups: GroupRepository
    @Inject lateinit var teachers: TeacherRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var topics: TopicRepository

    private var schoolId: UUID? = null
    private var subjectMap: Map<UUID, Subject> = emptyMap()
    private var groupMap: Map<UUID, StudyGroup> = emptyMap()
    private var teacherMap: Map<UUID, Teacher> = emptyMap()
    private var userMap: Map<UUID, UserAccount> = emptyMap()
    private var studentNames: MutableMap<UUID, String> = mutableMapOf()
    private var topicTitles: MutableMap<UUID, String> = mutableMapOf()

    fun load(school: UUID) {
        if (schoolId == school) return
        schoolId = school
        subjectMap = subjects.listInSchool(school).associateBy { it.id }
        groupMap = groups.listInSchool(school).associateBy { it.id }
        teacherMap = teachers.listInSchool(school).associateBy { it.id }
        userMap = users.findAllByIds(teacherMap.values.map { it.userId }).associateBy { it.id }
        studentNames = mutableMapOf()
        topicTitles = mutableMapOf()
    }

    fun subject(id: UUID?): Subject? = id?.let { subjectMap[it] }
    fun subjectName(id: UUID?): String = subject(id)?.name ?: "—"
    fun colorKey(subjectId: UUID?): String = subject(subjectId)?.colorKey ?: "blue"

    fun group(id: UUID?): StudyGroup? = id?.let { groupMap[it] }
    fun groupName(id: UUID?): String = group(id)?.name ?: "—"

    fun teacher(id: UUID?): Teacher? = id?.let { teacherMap[it] }

    fun teacherName(id: UUID?): String {
        val teacher = teacher(id) ?: return "—"
        val user = userMap[teacher.userId] ?: return "—"
        return "${user.lastName} ${user.firstName}"
    }

    fun teacherUser(id: UUID?): UserAccount? = teacher(id)?.let { userMap[it.userId] }

    fun studentName(id: UUID): String = studentNames.getOrPut(id) {
        val school = schoolId ?: return@getOrPut "—"
        students.findInSchool(id, school)?.let { "${it.lastName} ${it.firstName}" } ?: "—"
    }

    fun primeStudents(ids: Collection<UUID>) {
        val school = schoolId ?: return
        val missing = ids.filterNot { studentNames.containsKey(it) }
        if (missing.isEmpty()) return
        students.findAllByIds(school, missing).forEach {
            studentNames[it.id] = "${it.lastName} ${it.firstName}"
        }
    }

    fun topicTitle(id: UUID?): String? {
        if (id == null) return null
        val school = schoolId ?: return null
        return topicTitles.getOrPut(id) { topics.findInSchool(id, school)?.title ?: "—" }
    }

    fun allSubjects(): List<Subject> = subjectMap.values.sortedBy { it.name }
    fun allGroups(): List<StudyGroup> = groupMap.values.sortedWith(compareBy({ it.gradeLevel }, { it.name }))
    fun allTeachers(): List<Teacher> = teacherMap.values.toList()
}
