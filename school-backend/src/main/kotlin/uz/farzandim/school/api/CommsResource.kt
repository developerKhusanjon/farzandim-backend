package uz.farzandim.school.api

import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import uz.farzandim.school.dto.MessageBody
import uz.farzandim.school.dto.MessageDto
import uz.farzandim.school.dto.NotificationDto
import uz.farzandim.school.service.CommsService
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import java.util.UUID

@Path("")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class CommsResource {

    @Inject lateinit var comms: CommsService
    @Inject lateinit var currentUser: CurrentUser

    @GET @Path("/messages")
    fun messages(@QueryParam("limit") limit: Int?): ApiResponse<List<MessageDto>> =
        ApiResponse.of(comms.listMessages(currentUser.requireSchoolId(), (limit ?: 50).coerceIn(1, 200)))

    @POST @Path("/messages")
    fun send(@Valid body: MessageBody): ApiResponse<MessageDto> =
        ApiResponse.of(comms.sendMessage(currentUser.requireSchoolId(), body))

    @GET @Path("/notifications")
    fun notifications(@QueryParam("limit") limit: Int?): ApiResponse<List<NotificationDto>> =
        ApiResponse.of(comms.listNotifications(currentUser.userId, (limit ?: 50).coerceIn(1, 200)))

    @GET @Path("/notifications/unread-count")
    fun unread(): ApiResponse<Map<String, Long>> =
        ApiResponse.of(mapOf("count" to comms.unreadCount(currentUser.userId)))

    @POST @Path("/notifications/{id}/read")
    fun markRead(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        comms.markRead(currentUser.userId, id)
        return ApiResponse.of(mapOf("ok" to true))
    }

    @POST @Path("/notifications/read-all")
    fun markAllRead(): ApiResponse<Map<String, Boolean>> {
        comms.markAllRead(currentUser.userId)
        return ApiResponse.of(mapOf("ok" to true))
    }
}
