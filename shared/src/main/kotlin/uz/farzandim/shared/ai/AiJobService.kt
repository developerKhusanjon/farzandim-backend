package uz.farzandim.shared.ai

import com.fasterxml.jackson.databind.JsonNode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.AiJob
import uz.farzandim.shared.domain.JobStatus
import uz.farzandim.shared.repo.AiJobRepository
import uz.farzandim.shared.util.Json
import java.time.Instant
import java.util.UUID

/** AI job turlari — `ai_jobs.type` ustuni. */
object JobTypes {
    const val LESSON_SUMMARY = "LESSON_SUMMARY"
    const val HOMEWORK_GENERATE = "HOMEWORK_GENERATE"
    const val HOMEWORK_PARENT_HINT = "HOMEWORK_PARENT_HINT"
    const val HOMEWORK_VARIANTS = "HOMEWORK_VARIANTS"
    const val HOMEWORK_ASSIST = "HOMEWORK_ASSIST"
    const val ASSESSMENT_GENERATE = "ASSESSMENT_GENERATE"
    const val ASSESSMENT_ANALYZE = "ASSESSMENT_ANALYZE"
    const val RECOMMENDATIONS = "RECOMMENDATIONS"
    const val CHAT = "CHAT"
    const val EMBEDDING = "EMBEDDING"
    const val PERFORMANCE_RECOMPUTE = "PERFORMANCE_RECOMPUTE"
}

data class JobSpec(
    val type: String,
    val schoolId: UUID?,
    val createdBy: UUID?,
    val entityType: String? = null,
    val entityId: UUID? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val idempotencyKey: String? = null,
    val priority: Int = 100,
)

data class JobView(
    val id: UUID,
    val type: String,
    val status: JobStatus,
    val result: JsonNode?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

/**
 * AI ishlarining hisobi.
 *
 * Interaktiv endpointlar `runNow` ni chaqiradi: job yozuvi yaratiladi,
 * ish shu yerda bajariladi, natija darhol qaytadi. Job yozuvi baribir
 * qoladi — kim, qachon, qancha token sarfladi degan savol javobsiz
 * qolmasin va idempotentlik ishlasin.
 *
 * Fonda bajariladigan ishlar (embedding, tungi qayta hisoblash) `enqueue`
 * bilan navbatga qo'yiladi va `AiJobWorker` ularni oladi.
 */
@ApplicationScoped
class AiJobService {

    private val log = Logger.getLogger(AiJobService::class.java)

    @Inject lateinit var jobs: AiJobRepository

    @Transactional
    fun enqueue(spec: JobSpec): AiJob {
        spec.idempotencyKey?.let { key ->
            jobs.findByIdempotencyKey(key)?.let { return it }
        }
        val job = AiJob().apply {
            this.schoolId = spec.schoolId
            this.createdBy = spec.createdBy
            this.type = spec.type
            this.entityType = spec.entityType
            this.entityId = spec.entityId
            this.idempotencyKey = spec.idempotencyKey
            this.payload = Json.write(spec.payload)
            this.priority = spec.priority
            this.status = JobStatus.QUEUED
        }
        jobs.persist(job)
        return job
    }

    /**
     * Ishni darhol bajaradi va natijani qaytaradi.
     * Xato bo'lsa job FAILED bo'lib yoziladi va istisno yuqoriga uzatiladi.
     */
    fun <T : Any> runNow(spec: JobSpec, work: () -> Pair<T, AiResult?>): T {
        val job = startJob(spec)
        return try {
            val (value, ai) = work()
            finishJob(job.id, Json.write(value), ai)
            value
        } catch (e: ApiException) {
            failJob(job.id, e.code.name, e.message)
            throw e
        } catch (e: Exception) {
            log.errorf(e, "AI job xatosi: type=%s id=%s", spec.type, job.id)
            failJob(job.id, "INTERNAL_ERROR", e.message ?: e::class.simpleName)
            throw e
        }
    }

    @Transactional
    fun startJob(spec: JobSpec): AiJob {
        val job = AiJob().apply {
            this.schoolId = spec.schoolId
            this.createdBy = spec.createdBy
            this.type = spec.type
            this.entityType = spec.entityType
            this.entityId = spec.entityId
            this.payload = Json.write(spec.payload)
            this.priority = spec.priority
            this.status = JobStatus.RUNNING
            this.attempts = 1
            this.startedAt = Instant.now()
        }
        jobs.persist(job)
        return job
    }

    @Transactional
    fun finishJob(jobId: UUID, resultJson: String, ai: AiResult?) {
        val job = jobs.findById(jobId) ?: return
        job.status = JobStatus.DONE
        job.result = resultJson
        job.finishedAt = Instant.now()
        ai?.let {
            job.model = it.model
            job.inputTokens = it.inputTokens
            job.outputTokens = it.outputTokens
        }
        job.touch()
    }

    @Transactional
    fun failJob(jobId: UUID, code: String, message: String?) {
        val job = jobs.findById(jobId) ?: return
        job.status = JobStatus.FAILED
        job.errorCode = code
        job.errorMessage = message?.take(500)
        job.finishedAt = Instant.now()
        job.touch()
    }

    fun view(jobId: UUID, schoolId: UUID?): JobView {
        val job = jobs.findById(jobId) ?: throw ApiException.notFound("Job", jobId)
        if (schoolId != null && job.schoolId != null && job.schoolId != schoolId) {
            throw ApiException.notFound("Job", jobId)
        }
        return JobView(
            id = job.id,
            type = job.type,
            status = job.status,
            result = job.result?.let { Json.node(it) },
            errorCode = job.errorCode,
            errorMessage = job.errorMessage,
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
        )
    }
}
