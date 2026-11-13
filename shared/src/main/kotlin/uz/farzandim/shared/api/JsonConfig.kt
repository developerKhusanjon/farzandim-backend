package uz.farzandim.shared.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Uchala servis uchun umumiy JSON sozlamasi.
 *
 * Dars vaqtlari shartnomada "08:30" ko'rinishida (docs/02-data-model.md);
 * Jackson'ning standarti "08:30:00" — web va mobil ilova uni ekranda xom
 * ko'rsatardi. O'qishda ikkala ko'rinish ham qabul qilinadi (JavaTimeModule).
 */
@Singleton
class JsonConfig : ObjectMapperCustomizer {
    /**
     * Eng oxirida: Quarkus JavaTimeModule'ni ham customizer orqali qo'shadi, Jackson'da
     * esa keyin qo'shilgan modulning serializer'i ustun turadi.
     */
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
