package uz.farzandim.shared.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/**
 * Kotlin resurs metodining non-null query parametri berilmasa (`GET /gradebook`
 * `?groupId=` siz) Kotlin metodga kirishda NPE tashlaydi. Bu mijoz xatosi —
 * javob 500 "Ichki xatolik" emas, qaysi parametr yetishmayotganini aytadigan
 * VALIDATION_ERROR bo'lishi kerak.
 */
class FallbackExceptionMapperTest {

    /** Haqiqiy resursga o'xshash: paket nomida `.api.`, parametr non-null */
    class FakeGradebookResource {
        fun gradebook(groupId: UUID, subjectId: UUID): String = "$groupId/$subjectId"
    }

    /** Kotlin kompilyatori yaratgan haqiqiy NPE (sun'iy xabar emas) */
    private fun missingParameterNpe(): Throwable {
        val method = FakeGradebookResource::class.java
            .getMethod("gradebook", UUID::class.java, UUID::class.java)
        return try {
            method.invoke(FakeGradebookResource(), null, UUID.randomUUID())
            error("NPE kutilgan edi")
        } catch (e: InvocationTargetException) {
            e.targetException
        }
    }

    private val mapper = FallbackExceptionMapper()

    @Test
    fun `missing non-null resource parameter is a validation error naming the parameter`() {
        val response = mapper.toResponse(missingParameterNpe())

        assertEquals(ErrorCode.VALIDATION_ERROR.status, response.status)
        val body = (response.entity as ApiErrorResponse).error
        assertEquals(ErrorCode.VALIDATION_ERROR.name, body.code)
        assertEquals(mapOf("groupId" to "Majburiy parametr"), body.fields)
    }

    @Test
    fun `null parameter inside a service is still an internal error`() {
        val npe = NullPointerException(
            "Parameter specified as non-null is null: method uz.farzandim.school.service.GradeService.save, parameter value",
        ).apply {
            stackTrace = arrayOf(StackTraceElement("uz.farzandim.school.service.GradeService", "save", "GradeService.kt", 42))
        }

        val response = mapper.toResponse(npe)

        assertEquals(500, response.status)
        assertEquals(ErrorCode.INTERNAL_ERROR.name, (response.entity as ApiErrorResponse).error.code)
    }

    @Test
    fun `ordinary null pointer stays an internal error`() {
        val response = mapper.toResponse(NullPointerException("boshqa xato"))

        assertEquals(500, response.status)
    }
}
