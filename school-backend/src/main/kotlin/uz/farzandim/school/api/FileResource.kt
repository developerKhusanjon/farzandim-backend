package uz.farzandim.school.api

import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import uz.farzandim.shared.api.ApiResponse
import uz.farzandim.shared.security.CurrentUser
import uz.farzandim.shared.security.Roles
import uz.farzandim.shared.storage.FileDto
import uz.farzandim.shared.storage.FileService
import uz.farzandim.shared.storage.UploadRequest
import uz.farzandim.shared.storage.UploadTicket
import java.util.UUID

/**
 * Fayl yuklash oqimi (bucket PRIVATE):
 *
 *   1. POST /files/upload-url     → presigned PUT URL
 *   2. Brauzer PUT qiladi          → to'g'ridan-to'g'ri Spaces'ga
 *   3. POST /files/{id}/confirm    → backend HEAD bilan tekshiradi
 *   4. GET  /files/{id}/download   → 5 daqiqalik presigned GET
 *
 * Baytlar backend orqali o'tmaydi.
 */
@Path("/files")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.SCHOOL_ADMIN, Roles.TEACHER, Roles.SUPER_ADMIN)
class FileResource {

    @Inject lateinit var files: FileService
    @Inject lateinit var currentUser: CurrentUser

    @POST @Path("/upload-url")
    fun uploadUrl(@Valid body: UploadRequest): ApiResponse<UploadTicket> =
        ApiResponse.of(files.requestUpload(body, currentUser.schoolIdOrNull(), currentUser.userId))

    @POST @Path("/{id}/confirm")
    fun confirm(@PathParam("id") id: UUID): ApiResponse<FileDto> =
        ApiResponse.of(files.confirmUpload(id, currentUser.schoolIdOrNull(), currentUser.userId))

    @GET @Path("/{id}/download")
    fun download(@PathParam("id") id: UUID): ApiResponse<Map<String, String>> =
        ApiResponse.of(mapOf("url" to files.downloadUrl(id, currentUser.schoolIdOrNull(), currentUser.userId)))

    @DELETE @Path("/{id}")
    fun delete(@PathParam("id") id: UUID): ApiResponse<Map<String, Boolean>> {
        files.delete(id, currentUser.schoolIdOrNull(), currentUser.userId)
        return ApiResponse.of(mapOf("ok" to true))
    }
}
