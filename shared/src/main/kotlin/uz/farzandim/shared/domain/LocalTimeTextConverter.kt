package uz.farzandim.shared.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.time.LocalTime

/**
 * Dars vaqti (`time without time zone`) — vaqt mintaqasisiz, matn sifatida.
 *
 * `quarkus.hibernate-orm.jdbc.timezone=UTC` bo'lganda Hibernate `LocalTime` ni
 * java.sql.Time orqali UTC kalendari bilan bog'laydi, Time esa JVM mintaqasida
 * 1970-01-01 sanasiga bog'langan. Toshkent o'sha sanada UTC+6 bo'lgani uchun
 * dev kompyuterda bazadagi 08:30 API'da 14:30 bo'lib chiqardi (UTC'dagi serverda
 * ko'rinmasdi). Matn sifatida bog'lash — JDBC URL'dagi `stringtype=unspecified`
 * bilan Postgres qiymatni o'zi `time` ga o'giradi — har qanday muhitda bir xil.
 */
@Converter
class LocalTimeTextConverter : AttributeConverter<LocalTime, String> {
    override fun convertToDatabaseColumn(attribute: LocalTime?): String? = attribute?.toString()

    override fun convertToEntityAttribute(dbData: String?): LocalTime? = dbData?.let(LocalTime::parse)
}
