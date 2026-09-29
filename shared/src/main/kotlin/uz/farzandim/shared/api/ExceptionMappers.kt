package uz.farzandim.shared.api

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import java.util.UUID

@Provider
class ApiExceptionMapper : ExceptionMapper<ApiException> {
    override fun toResponse(e: ApiException): Response =
        Response.status(e.code.status)
            .entity(ApiErrorResponse(ApiErrorBody(e.code.name, e.message, e.fields)))
            .build()
}

@Provider
class ConstraintViolationMapper : ExceptionMapper<ConstraintViolationException> {
    override fun toResponse(e: ConstraintViolationException): Response {
        val fields = e.constraintViolations.associate { v ->
            // "createStudent.body.firstName" → "firstName"
            v.propertyPath.toString().substringAfterLast('.') to (v.message ?: "noto'g'ri qiymat")
        }
        return Response.status(ErrorCode.VALIDATION_ERROR.status)
            .entity(
                ApiErrorResponse(
                    ApiErrorBody(
                        ErrorCode.VALIDATION_ERROR.name,
                        "Kiritilgan ma'lumotda xatolik bor",
                        fields,
                    ),
                ),
            )
            .build()
    }
}

@Provider
class NotFoundMapper : ExceptionMapper<NotFoundException> {
    override fun toResponse(e: NotFoundException): Response =
        Response.status(404)
            .entity(ApiErrorResponse(ApiErrorBody(ErrorCode.NOT_FOUND.name, "Resurs topilmadi")))
            .build()
}

/**
 * Baza cheklovlarining tushunarli xabarga aylanishi.
 *
 * Unikal indeks buzilganda foydalanuvchi "Ichki xatolik" emas, nima band
 * ekanini ko'rishi kerak. Istisno tranzaksiya chegarasida
 * `RollbackException` ichiga o'ralgani uchun sabablar zanjirini kezamiz.
 */
private object ConstraintMessages {
    private val byConstraint = mapOf(
        "users_phone_key" to ("phone" to "Bu telefon raqami allaqachon ro'yxatdan o'tgan"),
        "users_email_key" to ("email" to "Bu email allaqachon ro'yxatdan o'tgan"),
        "users_username_key" to ("username" to "Bu login band"),
        "schools_short_name_key" to ("shortName" to "Bu qisqa nom band"),
        "schools_tin_key" to ("tin" to "Bu STIR bilan maktab allaqachon ro'yxatdan o'tgan"),
        "groups_name_key" to ("name" to "Bu o'quv yilida shunday nomli guruh bor"),
        "teachers_user_key" to ("userId" to "Bu foydalanuvchi allaqachon o'qituvchi"),
        "parents_user_key" to ("userId" to "Bu foydalanuvchi allaqachon ota-ona"),
        "teacher_assignments_key" to ("assignment" to "Bu biriktirish allaqachon mavjud"),
        "attendance_student_lesson_key" to ("attendance" to "Bu dars uchun davomat allaqachon belgilangan"),
        "homework_variants_key" to ("variant" to "Bu o'quvchi uchun variant allaqachon bor"),
        "homework_submissions_key" to ("submission" to "Bu o'quvchi uchun topshiriq allaqachon ochilgan"),
        "lessons_slot_date_key" to ("lesson" to "Bu sana uchun dars allaqachon yaratilgan"),
        "interests_student_key" to ("key" to "Bu qiziqish allaqachon qo'shilgan"),
        "files_object_key_key" to ("objectKey" to "Bu fayl kaliti band"),
        "ai_jobs_idempotency_key" to ("idempotencyKey" to "Bu ish allaqachon navbatga qo'yilgan"),
        "device_tokens_token_key" to ("token" to "Bu qurilma allaqachon ro'yxatdan o'tgan"),
    )

    fun resolve(throwable: Throwable): ApiException? {
        var cause: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            val name = cause::class.java.name
            if (name == "org.hibernate.exception.ConstraintViolationException" ||
                name == "org.postgresql.util.PSQLException"
            ) {
                val text = cause.message.orEmpty()
                byConstraint.forEach { (constraint, pair) ->
                    if (text.contains(constraint)) {
                        return ApiException(ErrorCode.CONFLICT, pair.second, mapOf(pair.first to pair.second))
                    }
                }
                if (text.contains("violates foreign key constraint")) {
                    return ApiException(ErrorCode.VALIDATION_ERROR, "Bog'lanmagan yozuvga ishora qilindi")
                }
                if (text.contains("duplicate key value")) {
                    return ApiException(ErrorCode.CONFLICT, "Bunday yozuv allaqachon mavjud")
                }
                if (text.contains("violates check constraint")) {
                    return ApiException(ErrorCode.VALIDATION_ERROR, "Qiymat ruxsat etilgan doiradan tashqarida")
                }
            }
            cause = cause.cause
        }
        return null
    }
}

/**
 * Oxirgi to'siq: kutilmagan istisno hech qachon stacktrace bilan tashqariga chiqmaydi.
 * Loglarda traceId bo'yicha topiladi.
 */
@Provider
class FallbackExceptionMapper : ExceptionMapper<Throwable> {
    private val log = Logger.getLogger(FallbackExceptionMapper::class.java)

    override fun toResponse(e: Throwable): Response {
        ConstraintMessages.resolve(e)?.let { mapped ->
            return Response.status(mapped.code.status)
                .entity(ApiErrorResponse(ApiErrorBody(mapped.code.name, mapped.message, mapped.fields)))
                .build()
        }

        // Servis qatlamidagi ApiException tranzaksiya ichida o'ralib kelishi mumkin.
        generateSequence(e) { it.cause }.take(10).filterIsInstance<ApiException>().firstOrNull()?.let { api ->
            return Response.status(api.code.status)
                .entity(ApiErrorResponse(ApiErrorBody(api.code.name, api.message, api.fields)))
                .build()
        }

        if (e is WebApplicationException && e.response.status < 500) {
            return Response.status(e.response.status)
                .entity(ApiErrorResponse(ApiErrorBody(mapStatus(e.response.status), e.message ?: "So'rov rad etildi")))
                .build()
        }
        val traceId = UUID.randomUUID().toString().take(8)
        log.errorf(e, "Kutilmagan xato [%s]", traceId)
        return Response.status(500)
            .entity(
                ApiErrorResponse(
                    ApiErrorBody(ErrorCode.INTERNAL_ERROR.name, "Ichki xatolik yuz berdi", traceId = traceId),
                ),
            )
            .build()
    }

    private fun mapStatus(status: Int): String = when (status) {
        401 -> ErrorCode.UNAUTHORIZED.name
        403 -> ErrorCode.FORBIDDEN.name
        404 -> ErrorCode.NOT_FOUND.name
        409 -> ErrorCode.CONFLICT.name
        422 -> ErrorCode.VALIDATION_ERROR.name
        429 -> ErrorCode.RATE_LIMITED.name
        else -> ErrorCode.VALIDATION_ERROR.name
    }
}
