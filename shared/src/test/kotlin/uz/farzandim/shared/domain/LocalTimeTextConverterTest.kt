package uz.farzandim.shared.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.TimeZone

/**
 * Dars vaqti bazadan qanday yozilgan bo'lsa, shunday qaytishi kerak — JVM qaysi
 * vaqt mintaqasida ishlashidan qat'i nazar (dev kompyuter: Asia/Tashkent,
 * u 1970-yilda UTC+6 bo'lgan; server: UTC).
 */
class LocalTimeTextConverterTest {

    private val converter = LocalTimeTextConverter()

    @Test
    fun `lesson time is stored as plain text`() {
        assertEquals("08:30", converter.convertToDatabaseColumn(LocalTime.of(8, 30)))
    }

    @Test
    fun `postgres time text is read back unchanged`() {
        // pgjdbc `time` ustunini getString bilan "08:30:00" ko'rinishida beradi
        assertEquals(LocalTime.of(8, 30), converter.convertToEntityAttribute("08:30:00"))
    }

    @Test
    fun `round trip does not depend on the jvm time zone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tashkent"))
            val stored = converter.convertToDatabaseColumn(LocalTime.of(8, 30))
            assertEquals(LocalTime.of(8, 30), converter.convertToEntityAttribute(stored))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `nulls pass through`() {
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
