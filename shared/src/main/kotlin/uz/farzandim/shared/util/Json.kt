package uz.farzandim.shared.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * `jsonb` ustunlar entity'da `String` sifatida turadi (JDBC URL'da
 * `stringtype=unspecified` — PostgreSQL matnni jsonb'ga o'zi keltiradi).
 * Bu Hibernate'ning JSON mapping "sehri"dan ko'ra bashoratliroq:
 * nima yozilsa — bazada aynan o'sha turadi.
 */
object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()

    fun write(value: Any?): String = mapper.writeValueAsString(value ?: emptyMap<String, Any>())

    fun node(raw: String?): JsonNode =
        if (raw.isNullOrBlank()) mapper.createObjectNode() else mapper.readTree(raw)

    fun obj(raw: String?): ObjectNode = node(raw) as? ObjectNode ?: mapper.createObjectNode()

    fun array(raw: String?): ArrayNode = node(raw) as? ArrayNode ?: mapper.createArrayNode()

    inline fun <reified T> read(raw: String?, fallback: T): T =
        if (raw.isNullOrBlank()) fallback else runCatching { mapper.readValue<T>(raw) }.getOrDefault(fallback)

    fun emptyObject(): String = "{}"

    fun emptyArray(): String = "[]"

    /**
     * Model javobidan JSON ajratib olish. Claude ba'zan ```json ... ``` bilan
     * o'raydi yoki oldidan bir gap yozadi — shu holatlarni kechiramiz.
     */
    fun extract(raw: String): JsonNode? {
        val trimmed = raw.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)?.groupValues?.get(1)?.trim()
        val candidates = listOfNotNull(
            fenced,
            trimmed,
            trimmed.substring(
                trimmed.indexOfFirst { it == '{' || it == '[' }.coerceAtLeast(0),
                (trimmed.indexOfLast { it == '}' || it == ']' } + 1).coerceAtLeast(0),
            ).takeIf { it.isNotBlank() },
        )
        for (candidate in candidates) {
            runCatching { return mapper.readTree(candidate) }
        }
        return null
    }
}
