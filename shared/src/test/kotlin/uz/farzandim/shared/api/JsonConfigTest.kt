package uz.farzandim.shared.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.quarkus.jackson.ObjectMapperCustomizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime

/** LocalTime JSON formati */
class JsonConfigTest {

    /** Quarkus standart mapper'i kabi sozlangan */
    private val mapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .also { JsonConfig().customize(it) }

    @Test
    fun `local time is written as hours and minutes`() {
        assertEquals("\"08:30\"", mapper.writeValueAsString(LocalTime.of(8, 30)))
    }

    /** Quarkus JavaTimeModule'ni customizer orqali qo'shadi — bizniki undan keyin ishlashi kerak */
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
