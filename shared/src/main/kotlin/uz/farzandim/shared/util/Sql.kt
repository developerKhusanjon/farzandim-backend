package uz.farzandim.shared.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Native so'rov natijalarini o'qish.
 *
 * JDBC drayveri va Hibernate versiyasiga qarab `date` ustuni
 * `java.sql.Date` yoki `java.time.LocalDate` bo'lib kelishi mumkin
 * (`timestamptz` uchun ham xuddi shunday). To'g'ridan-to'g'ri `as java.sql.Date`
 * deb yozish — versiya yangilanganda ClassCastException.
 */
object Sql {

    fun date(value: Any?): LocalDate? = when (value) {
        null -> null
        is LocalDate -> value
        is java.sql.Date -> value.toLocalDate()
        is java.sql.Timestamp -> value.toLocalDateTime().toLocalDate()
        is LocalDateTime -> value.toLocalDate()
        is OffsetDateTime -> value.toLocalDate()
        is Instant -> value.atZone(java.time.ZoneOffset.UTC).toLocalDate()
        else -> LocalDate.parse(value.toString().substringBefore(' ').substringBefore('T'))
    }

    fun requireDate(value: Any?): LocalDate =
        date(value) ?: error("Sana kutilgan edi, keldi: $value")

    fun instant(value: Any?): Instant? = when (value) {
        null -> null
        is Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        is LocalDateTime -> value.toInstant(java.time.ZoneOffset.UTC)
        is java.sql.Date -> value.toInstant()
        else -> Instant.parse(value.toString())
    }

    fun requireInstant(value: Any?): Instant =
        instant(value) ?: error("Vaqt kutilgan edi, keldi: $value")
}
