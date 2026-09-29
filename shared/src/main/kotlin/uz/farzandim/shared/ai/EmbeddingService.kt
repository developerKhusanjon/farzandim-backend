package uz.farzandim.shared.ai

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import uz.farzandim.shared.util.Json
import java.util.UUID

data class SemanticHit(
    val entityType: String,
    val entityId: UUID,
    val studentId: UUID?,
    val content: String,
    val score: Double,
)

/**
 * Semantik qidiruv (pgvector).
 *
 * MUHIM: bu ODDIY qidiruvning o'rnini bosmaydi. Ism, telefon, email
 * bo'yicha qidiruv `pg_trgm` bilan ketadi — u aniq, tez va arzon.
 * Vektor qidiruv faqat "ma'noga ko'ra" savollar uchun:
 * «kasrlar mavzusida qiynalgan o'quvchilar», «darsga qiziqish yo'qolgani
 * haqida yozilgan kuzatuvlar». AI qidiruv ikkalasini birlashtiradi:
 * avval filtr (maktab, guruh, sana), keyin filtr ichida vektor yaqinligi.
 *
 * Vektor ustuni Hibernate'ga mapping qilinmagan — `vector` tipi uchun
 * native SQL ishonchliroq va tushunarliroq.
 */
@ApplicationScoped
class EmbeddingService {

    private val log = Logger.getLogger(EmbeddingService::class.java)

    @Inject @RestClient lateinit var api: EmbeddingApi
    @Inject lateinit var settings: EmbeddingSettings
    @Inject lateinit var em: EntityManager

    private val apiKey: String get() = settings.apiKey().orElse("")

    val enabled: Boolean get() = settings.enabled() && apiKey.isNotBlank()

    fun embed(texts: List<String>, inputType: String = "document"): List<List<Double>> {
        if (!enabled || texts.isEmpty()) return emptyList()
        val response = api.embed(
            "Bearer $apiKey",
            EmbeddingRequest(texts.map { it.take(8000) }, settings.model(), inputType),
        )
        return response.data.sortedBy { it.index }.map { it.embedding }
    }

    @Transactional
    fun index(
        schoolId: UUID,
        entityType: String,
        entityId: UUID,
        content: String,
        studentId: UUID? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        if (!enabled || content.isBlank()) return
        val vector = embed(listOf(content)).firstOrNull() ?: return
        if (vector.size != settings.dimensions()) {
            log.warnf(
                "Embedding o'lchami mos emas: kutilgan %d, kelgan %d — migratsiyadagi vector(N) ni tekshiring",
                settings.dimensions(), vector.size,
            )
            return
        }

        em.createNativeQuery(
            """
            insert into ai_embeddings (id, school_id, entity_type, entity_id, student_id, chunk_index,
                                       content, embedding, model, metadata)
            values (gen_random_uuid(), :school, :type, :entity, :student, 0,
                    :content, cast(:embedding as vector), :model, cast(:metadata as jsonb))
            on conflict (entity_type, entity_id, chunk_index)
            do update set content = excluded.content,
                          embedding = excluded.embedding,
                          model = excluded.model,
                          metadata = excluded.metadata,
                          created_at = now()
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("type", entityType)
            .setParameter("entity", entityId)
            .setParameter("student", studentId)
            .setParameter("content", content.take(8000))
            .setParameter("embedding", toVectorLiteral(vector))
            .setParameter("model", settings.model())
            .setParameter("metadata", Json.write(metadata))
            .executeUpdate()
    }

    /**
     * Kosinus yaqinligi bo'yicha qidiruv. Filtrlar avval qo'llanadi —
     * "butun bazadan qidirib, keyin maktabni tekshirish" xavfli va sekin.
     */
    fun search(
        schoolId: UUID,
        query: String,
        entityTypes: List<String> = emptyList(),
        studentId: UUID? = null,
        limit: Int = 10,
    ): List<SemanticHit> {
        if (!enabled) return emptyList()
        val vector = embed(listOf(query), inputType = "query").firstOrNull() ?: return emptyList()

        val sql = buildString {
            append(
                """
                select entity_type, entity_id, student_id, content,
                       1 - (embedding <=> cast(:embedding as vector)) as score
                from ai_embeddings
                where school_id = :school
                """.trimIndent(),
            )
            if (entityTypes.isNotEmpty()) append("\n  and entity_type in (:types)")
            if (studentId != null) append("\n  and student_id = :student")
            append("\norder by embedding <=> cast(:embedding as vector)\nlimit :limit")
        }

        val nativeQuery = em.createNativeQuery(sql)
            .setParameter("school", schoolId)
            .setParameter("embedding", toVectorLiteral(vector))
            .setParameter("limit", limit)
        if (entityTypes.isNotEmpty()) nativeQuery.setParameter("types", entityTypes)
        if (studentId != null) nativeQuery.setParameter("student", studentId)

        @Suppress("UNCHECKED_CAST")
        val rows = nativeQuery.resultList as List<Array<Any?>>
        return rows.map { row ->
            SemanticHit(
                entityType = row[0] as String,
                entityId = row[1] as UUID,
                studentId = row[2] as UUID?,
                content = row[3] as String,
                score = (row[4] as Number).toDouble(),
            )
        }
    }

    /** pgvector matn ko'rinishi: `[0.1,0.2,...]` */
    private fun toVectorLiteral(vector: List<Double>): String =
        vector.joinToString(",", prefix = "[", postfix = "]") { it.toString() }
}
