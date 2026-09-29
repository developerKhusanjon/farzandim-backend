package uz.farzandim.school.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import uz.farzandim.shared.ai.AiJobHandler
import uz.farzandim.shared.ai.AiResult
import uz.farzandim.shared.ai.EmbeddingService
import uz.farzandim.shared.ai.JobTypes
import uz.farzandim.shared.domain.AiJob
import uz.farzandim.shared.util.Json

/**
 * Fonda indekslash: dars xulosasi, vazifa matni, kuzatuv qaydlari
 * `ai_embeddings` ga tushadi va semantik qidiruvga kiradi.
 *
 * Bu ish interaktiv emas — foydalanuvchi kutmaydi, shuning uchun
 * navbat orqali ketadi va xato bo'lsa qayta uriniladi.
 */
@ApplicationScoped
class EmbeddingJobHandler : AiJobHandler {

    @Inject lateinit var embeddings: EmbeddingService

    override val type: String = JobTypes.EMBEDDING

    override fun handle(job: AiJob): Pair<String, AiResult?> {
        val schoolId = job.schoolId ?: return Json.write(mapOf("skipped" to "maktab ko'rsatilmagan")) to null
        val entityId = job.entityId ?: return Json.write(mapOf("skipped" to "obyekt ko'rsatilmagan")) to null
        val payload = Json.obj(job.payload)
        val content = payload.path("content").asText("")
        if (content.isBlank()) return Json.write(mapOf("skipped" to "matn bo'sh")) to null

        embeddings.index(
            schoolId = schoolId,
            entityType = job.entityType ?: "NOTE",
            entityId = entityId,
            content = content,
            studentId = payload.path("studentId").asText(null)
                ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() },
        )
        return Json.write(mapOf("indexed" to true, "chars" to content.length)) to null
    }
}
