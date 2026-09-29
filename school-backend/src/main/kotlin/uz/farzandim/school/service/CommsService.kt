package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import uz.farzandim.school.dto.MessageBody
import uz.farzandim.school.dto.MessageDto
import uz.farzandim.school.dto.NotificationDto
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.MessageEntity
import uz.farzandim.shared.domain.MessageRecipient
import uz.farzandim.shared.domain.RecipientType
import uz.farzandim.shared.notify.NotificationService
import uz.farzandim.shared.repo.MessageRecipientRepository
import uz.farzandim.shared.repo.MessageRepository
import uz.farzandim.shared.repo.NotificationRepository
import uz.farzandim.shared.repo.StudentRepository
import uz.farzandim.shared.repo.UserRepository
import uz.farzandim.shared.security.CurrentUser
import java.util.UUID

@ApplicationScoped
class CommsService {

    @Inject lateinit var messages: MessageRepository
    @Inject lateinit var recipients: MessageRecipientRepository
    @Inject lateinit var notifications: NotificationRepository
    @Inject lateinit var notificationService: NotificationService
    @Inject lateinit var students: StudentRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var currentUser: CurrentUser
    @Inject lateinit var access: AccessControl
    @Inject lateinit var lookups: Lookups
    @Inject lateinit var mappers: Mappers

    fun listMessages(schoolId: UUID, limit: Int): List<MessageDto> {
        lookups.load(schoolId)
        val senderIds = mutableSetOf<UUID>()
        val all = messages.listForSchool(schoolId, limit).onEach { it.senderId?.let(senderIds::add) }
        val senders = users.findAllByIds(senderIds).associateBy { it.id }

        return all.map { message ->
            MessageDto(
                id = message.id,
                schoolId = message.schoolId,
                senderId = message.senderId,
                senderName = senders[message.senderId]?.let { "${it.lastName} ${it.firstName}" } ?: "Tizim",
                senderRole = message.senderRole,
                recipientType = message.recipientType.name,
                recipientLabel = when (message.recipientType) {
                    RecipientType.GROUP_PARENTS -> lookups.groupName(message.recipientGroupId) + " ota-onalari"
                    RecipientType.ALL -> "Barcha"
                    RecipientType.TEACHER -> "O'qituvchilar"
                    RecipientType.PARENT -> "Ota-ona"
                },
                subject = message.subject,
                body = message.body,
                category = message.category,
                colorKey = "blue",
                sentAt = message.sentAt,
                isRead = true,
            )
        }
    }

    @Transactional
    fun sendMessage(schoolId: UUID, body: MessageBody): MessageDto {
        lookups.load(schoolId)
        val recipientType = runCatching { RecipientType.valueOf(body.recipientType) }
            .getOrElse { throw ApiException.validation("recipientType noto'g'ri") }

        val targetUserIds: List<UUID> = when (recipientType) {
            RecipientType.GROUP_PARENTS -> {
                val groupId = body.groupId ?: throw ApiException.validation("groupId kerak")
                access.assertCanTouchGroup(groupId)
                notificationService.parentUserIdsOf(students.listByGroup(schoolId, groupId).map { it.id })
            }
            RecipientType.PARENT -> {
                if (body.recipientIds.isEmpty()) throw ApiException.validation("recipientIds bo'sh")
                notificationService.parentUserIdsOf(body.recipientIds)
            }
            RecipientType.TEACHER -> {
                access.assertAdmin()
                users.listBySchoolAndRole(schoolId, uz.farzandim.shared.domain.Role.TEACHER).map { it.id }
            }
            RecipientType.ALL -> {
                access.assertAdmin()
                users.list("schoolId", schoolId).map { it.id }
            }
        }

        val message = MessageEntity().apply {
            this.schoolId = schoolId
            this.senderId = currentUser.userId
            this.senderRole = currentUser.role.name
            this.recipientType = recipientType
            this.recipientGroupId = body.groupId
            this.subject = body.subject
            this.body = body.body
            this.category = body.category
        }
        messages.persist(message)

        targetUserIds.distinct().forEach { userId ->
            recipients.persist(
                MessageRecipient().apply {
                    this.id.messageId = message.id
                    this.id.userId = userId
                },
            )
        }

        notificationService.notifyUsers(
            targetUserIds,
            schoolId,
            body.category,
            title = body.subject.ifBlank { "Yangi xabar" },
            body = body.body.take(140),
            link = "/messages/${message.id}",
        )

        return listMessages(schoolId, 1).firstOrNull() ?: throw ApiException.notFound("Xabar")
    }

    fun listNotifications(userId: UUID, limit: Int): List<NotificationDto> =
        notifications.listForUser(userId, limit).map { mappers.notification(it) }

    fun unreadCount(userId: UUID): Long = notifications.countUnread(userId)

    @Transactional
    fun markRead(userId: UUID, id: UUID) {
        if (notifications.markRead(id, userId) == 0) throw ApiException.notFound("Bildirishnoma", id)
    }

    @Transactional
    fun markAllRead(userId: UUID) {
        notifications.markAllRead(userId)
    }
}
