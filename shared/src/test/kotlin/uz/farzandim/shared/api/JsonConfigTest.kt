package uz.farzandim.shared.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.quarkus.jackson.ObjectMapperCustomizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * Dars vaqtlari shartnomada "08:30" (docs/02-data-model.md). Jackson'ning
 * standarti esa "08:30:00" — web va mobil ilova uni ekranda xom ko'rsatadi.
 */
class JsonConfigTest {

    /**
     * Quarkus standart mapper'i kabi: JavaTimeModule ro'yxatda, sanalar raqam emas
     * (`quarkus.jackson.write-dates-as-timestamps=false`), keyin bizning customizer.
     */
    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .also { JsonConfig().customize(it) }

    @Test
    fun `local time is written as hours and minutes`() {
        assertEquals("\"08:30\"", mapper.writeValueAsString(LocalTime.of(8, 30)))
    }

    /**
     * Quarkus o'zining JavaTimeModule'ini ham customizer orqali ro'yxatga oladi.
     * Jackson'da keyin qo'shilgan modul serializer'i ustun turadi — shuning uchun
     * bizniki eng oxirida ishlashi shart (aks holda tirik servisda yana "08:30:00").
     */
    @Test
    fun `runs after every other customizer`() {
        assertEquals(ObjectMapperCustomizer.MINIMUM_PRIORITY, JsonConfig().priority())
    }

    @Test
    fun `local time is read with or without seconds`() {
        assertEquals(LocalTime.of(8, 30), mapper.readValue("\"08:30\"", LocalTime::class.java))
        assertEquals(LocalTime.of(8, 30), mapper.readValue("\"08:30:00\"", LocalTime::class.java))
    }
}
