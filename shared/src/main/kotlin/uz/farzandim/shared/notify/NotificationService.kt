package uz.farzandim.shared.notify

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.shared.domain.MessageCategory
import uz.farzandim.shared.domain.Notification
import uz.farzandim.shared.repo.NotificationRepository
import uz.farzandim.shared.repo.ParentRepository
import uz.farzandim.shared.repo.StudentParentRepository
import uz.farzandim.shared.util.Json
import java.time.Instant
import java.util.UUID

/**
 * Bildirishnomalar.
 *
 * Ikki qatlam:
 *   1. bazadagi yozuv — ilovadagi "qo'ng'iroq" ro'yxati, har doim qoladi;
 *   2. qurilmaga push — qulaylik, yetib bormasligi mumkin.
 *
 * Push `DeliveryDispatcher` orqali, tranzaksiya commit bo'lgandan keyin
 * yuboriladi. Shuning uchun bu yerda faqat hodisa otiladi.
 */
@ApplicationScoped
class NotificationService {

    @Inject lateinit var notifications: NotificationRepository
    @Inject lateinit var studentParents: StudentParentRepository
    @Inject lateinit var parents: ParentRepository
    @Inject lateinit var pushEvents: Event<PushRequested>

    @Transactional
    fun notifyUsers(
        userIds: Collection<UUID>,
        schoolId: UUID?,
        category: MessageCategory,
        title: String,
        body: String,
        link: String? = null,
        payload: Map<String, Any?> = emptyMap(),
        colorKey: String = "blue",
        iconKey: String = "bell",
    ) {
        if (userIds.isEmpty()) return

        val distinct = userIds.distinct()
        for (userId in distinct) {
            notifications.persist(
                Notification().apply {
                    this.userId = userId
                    this.schoolId = schoolId
                    this.type = category.name
                    this.title = title
                    this.body = body
                    this.link = link
                    this.payload = Json.write(payload)
                    this.colorKey = colorKey
                    this.iconKey = iconKey
                    this.pushSentAt = Instant.now()
                },
            )
        }

        pushEvents.fire(
            PushRequested(
                userIds = distinct,
                message = PushMessage(
                    title = title,
                    body = body,
                    data = buildMap {
                        put("category", category.name)
                        link?.let { put("link", it) }
                        payload.forEach { (key, value) -> value?.let { put(key, it.toString()) } }
                    },
                ),
            ),
        )
    }

    /** O'quvchilarning ota-onalari (user id). */
    fun parentUserIdsOf(studentIds: Collection<UUID>): List<UUID> {
        if (studentIds.isEmpty()) return emptyList()
        val links = studentParents.listByStudents(studentIds)
        if (links.isEmpty()) return emptyList()
        return parents.findAllByIds(links.map { it.parentId }.distinct()).map { it.userId }
    }

    fun notifyParentsOfStudents(
        studentIds: Collection<UUID>,
        schoolId: UUID?,
        category: MessageCategory,
        title: String,
        body: String,
        link: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        notifyUsers(parentUserIdsOf(studentIds), schoolId, category, title, body, link, payload)
    }
}
