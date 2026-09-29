package uz.farzandim.shared.security

import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import uz.farzandim.shared.api.ApiErrorBody
import uz.farzandim.shared.api.ApiErrorResponse
import uz.farzandim.shared.api.ErrorCode
import uz.farzandim.shared.domain.Role
import java.util.UUID

/**
 * `X-School-Id` sarlavhasini tokendagi maktab bilan solishtiradi.
 *
 * Sarlavha — qulaylik uchun (frontend qaysi kontekstda ishlayotganini
 * bildiradi), ruxsat manbai emas. Agar u tokendagi maktabdan farq qilsa,
 * so'rov 403 bilan to'xtaydi: aks holda o'qituvchi sarlavhani almashtirib
 * boshqa maktab ma'lumotini so'rashga urinib ko'rardi.
 *
 * SUPER_ADMIN — yagona istisno: u maktablar orasida harakatlanadi,
 * shuning uchun uning uchun sarlavha maktabni tanlaydi.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 10)
class TenantRequestFilter : ContainerRequestFilter {

    @Inject
    lateinit var currentUser: CurrentUser

    override fun filter(ctx: ContainerRequestContext) {
        if (!currentUser.authenticated) return

        val raw = ctx.getHeaderString(HEADER)?.trim()
        val requested = raw?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        if (raw != null && raw.isNotEmpty() && requested == null) {
            abort(ctx, ErrorCode.VALIDATION_ERROR, "$HEADER noto'g'ri formatda")
            return
        }

        val tokenSchool = currentUser.tokenSchoolId
        val role = runCatching { currentUser.role }.getOrNull()

        if (role == Role.SUPER_ADMIN) {
            currentUser.requestedSchoolId = requested
            return
        }

        if (requested != null && tokenSchool != null && requested != tokenSchool) {
            abort(ctx, ErrorCode.FORBIDDEN, "Boshqa maktab konteksti so'ralgan")
            return
        }

        currentUser.requestedSchoolId = tokenSchool
    }

    private fun abort(ctx: ContainerRequestContext, code: ErrorCode, message: String) {
        ctx.abortWith(
            Response.status(code.status)
                .entity(ApiErrorResponse(ApiErrorBody(code.name, message)))
                .build(),
        )
    }

    companion object {
        const val HEADER = "X-School-Id"
    }
}
