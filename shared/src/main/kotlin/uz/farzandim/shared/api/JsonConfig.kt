package uz.farzandim.shared.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Umumiy JSON sozlamasi: LocalTime "HH:mm" ko'rinishida yoziladi. */
@Singleton
class JsonConfig : ObjectMapperCustomizer {
    // Quarkus JavaTimeModule'ni ham customizer orqali qo'shadi — bizniki undan keyin ishlashi kerak
    override fun priority(): Int = ObjectMapperCustomizer.MINIMUM_PRIORITY

    override fun customize(mapper: ObjectMapper) {
        mapper.registerModule(
            SimpleModule("farzandim-time").addSerializer(LocalTime::class.java, LocalTimeSerializer(HOURS_MINUTES)),
        )
    }

    private companion object {
        val HOURS_MINUTES: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
