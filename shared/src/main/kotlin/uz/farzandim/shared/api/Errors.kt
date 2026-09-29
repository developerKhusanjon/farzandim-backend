package uz.farzandim.shared.api

import jakarta.ws.rs.core.Response

/** docs/03-api-contract.md — "Xato kodlari" bo'limi. */
enum class ErrorCode(val status: Int) {
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    VALIDATION_ERROR(422),
    CONFLICT(409),
    SCHOOL_CONTEXT_REQUIRED(400),
    SUBSCRIPTION_EXPIRED(402),
    /** Maktab tarifida yoki sozlamasida bu imkoniyat yo'q. */
    FEATURE_NOT_AVAILABLE(403),
    AI_QUOTA_EXCEEDED(429),
    RATE_LIMITED(429),
    AI_UNAVAILABLE(503),
    STORAGE_ERROR(502),
    INTERNAL_ERROR(500),
    ;

    val response: Response.Status? get() = Response.Status.fromStatusCode(status)
}

class ApiException(
    val code: ErrorCode,
    override val message: String,
    val fields: Map<String, String>? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        fun notFound(entity: String, id: Any? = null): ApiException =
            ApiException(ErrorCode.NOT_FOUND, if (id == null) "$entity topilmadi" else "$entity topilmadi: $id")

        fun forbidden(message: String = "Bu amalga ruxsat yo'q"): ApiException =
            ApiException(ErrorCode.FORBIDDEN, message)

        fun unauthorized(message: String = "Avtorizatsiya talab qilinadi"): ApiException =
            ApiException(ErrorCode.UNAUTHORIZED, message)

        fun validation(message: String, fields: Map<String, String>? = null): ApiException =
            ApiException(ErrorCode.VALIDATION_ERROR, message, fields)

        fun conflict(message: String): ApiException =
            ApiException(ErrorCode.CONFLICT, message)

        fun schoolContextRequired(): ApiException =
            ApiException(ErrorCode.SCHOOL_CONTEXT_REQUIRED, "X-School-Id sarlavhasi talab qilinadi")

        fun featureNotAvailable(message: String): ApiException =
            ApiException(ErrorCode.FEATURE_NOT_AVAILABLE, message)

        fun rateLimited(retryAfterSeconds: Long): ApiException =
            ApiException(ErrorCode.RATE_LIMITED, "Juda ko'p so'rov. $retryAfterSeconds soniyadan keyin urinib ko'ring")
    }
}
