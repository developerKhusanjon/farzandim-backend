package uz.farzandim.shared.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.time.LocalTime

/**
 * `time` ustunini matn sifatida o'qiydi va yozadi.
 * `jdbc.timezone=UTC` bilan Hibernate LocalTime'ni JVM mintaqasi orqali o'giradi
 * (Asia/Tashkent'da +6 soat). Matnni Postgres o'zi `time` ga o'giradi (stringtype=unspecified).
 */
@Converter
class LocalTimeTextConverter : AttributeConverter<LocalTime, String> {
    override fun convertToDatabaseColumn(attribute: LocalTime?): String? = attribute?.toString()

    override fun convertToEntityAttribute(dbData: String?): LocalTime? = dbData?.let(LocalTime::parse)
}
