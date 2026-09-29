package uz.farzandim.shared.ai

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Any
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import uz.farzandim.shared.domain.AiJob
import uz.farzandim.shared.domain.JobStatus
import uz.farzandim.shared.repo.AiJobRepository
import java.time.Instant
import java.util.UUID

/**
 * Fonda bajariladigan AI ishi.
 *
 * Har bir handler bitta `type` ni biladi. Handler'lar servis modullarida
 * e'lon qilinadi (ularga domen servislari kerak), worker esa umumiy.
 */
interface AiJobHandler {
    val type: String

    /** Natija JSON satri + (agar model chaqirilgan bo'lsa) token hisobi. */
    fun handle(job: AiJob): Pair<String, AiResult?>
}

/**
 * Navbatdan ish oluvchi.
 *
 * `FOR UPDATE SKIP LOCKED` — bir nechta pod bir vaqtda ishlaganda ham
 * bitta job ikki marta bajarilmaydi: birinchi pod qatorni qulflaydi,
 * qolganlari uni "o'tkazib yuboradi" va keyingisini oladi.
 *
 * Worker faqat `farzandim.ai.worker-enabled=true` bo'lgan servisda ishlaydi —
 * standart holatda school-backend'da. Mobile va admin faqat navbatga qo'yadi.
 */
@ApplicationScoped
class AiJobWorker {

    private val log = Logger.getLogger(AiJobWorker::class.java)
    private val workerId: String = "${java.net.InetAddress.getLocalHost().hostName}-${UUID.randomUUID().toString().take(8)}"

    @Inject lateinit var em: EntityManager
    @Inject lateinit var jobs: AiJobRepository
    @Inject lateinit var settings: AiSettings

    @Inject
    @Any
    lateinit var handlers: Instance<AiJobHandler>

    @Scheduled(
        every = "{farzandim.ai.worker-poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun poll() {
        if (!settings.workerEnabled()) return

        val claimed = claimBatch(settings.workerBatchSize())
        for (jobId in claimed) {
            runCatching { execute(jobId) }
                .onFailure { log.errorf(it, "Job bajarilmadi: %s", jobId) }
        }
    }

    /**
     * Ishni band qilish. SELECT ... FOR UPDATE SKIP LOCKED va UPDATE
     * bitta tranzaksiyada: qulf commit'gacha ushlab turiladi.
     */
    @Transactional
    fun claimBatch(limit: Int): List<UUID> {
        @Suppress("UNCHECKED_CAST")
        val ids = em.createNativeQuery(
            """
            select id from ai_jobs
            where status = 'QUEUED' and run_after <= now()
            order by priority, created_at
            limit :limit
            for update skip locked
            """.trimIndent(),
        )
            .setParameter("limit", limit)
            .resultList as List<UUID>

        if (ids.isEmpty()) return emptyList()

        em.createNativeQuery(
            """
            update ai_jobs
            set status = 'RUNNING',
                locked_by = :worker,
                locked_at = now(),
                started_at = coalesce(started_at, now()),
                attempts = attempts + 1,
                updated_at = now()
            where id in (:ids)
            """.trimIndent(),
        )
            .setParameter("worker", workerId)
            .setParameter("ids", ids)
            .executeUpdate()

        return ids
    }

    fun execute(jobId: UUID) {
        val job = loadJob(jobId) ?: return
        val handler = handlers.firstOrNull { it.type == job.type }
        if (handler == null) {
            fail(jobId, "NO_HANDLER", "«${job.type}» turi uchun handler ro'yxatdan o'tmagan", retry = false)
            return
        }

        try {
            val (result, ai) = handler.handle(job)
            complete(jobId, result, ai)
        } catch (e: Exception) {
            log.warnf(e, "Job xatosi: type=%s id=%s", job.type, jobId)
            fail(jobId, "EXECUTION_ERROR", e.message ?: e::class.simpleName, retry = true)
        }
    }

    @Transactional
    fun loadJob(jobId: UUID): AiJob? = jobs.findById(jobId)

    @Transactional
    fun complete(jobId: UUID, result: String, ai: AiResult?) {
        val job = jobs.findById(jobId) ?: return
        job.status = JobStatus.DONE
        job.result = result
        job.finishedAt = Instant.now()
        job.lockedBy = null
        ai?.let {
            job.model = it.model
            job.inputTokens = it.inputTokens
            job.outputTokens = it.outputTokens
        }
        job.touch()
    }

    @Transactional
    fun fail(jobId: UUID, code: String, message: String?, retry: Boolean) {
        val job = jobs.findById(jobId) ?: return
        job.errorCode = code
        job.errorMessage = message?.take(500)
        job.lockedBy = null

        if (retry && job.attempts < job.maxAttempts) {
            // Eksponensial kechikish: 30s, 2 daqiqa, 8 daqiqa
            val delaySeconds = 30L * Math.pow(4.0, (job.attempts - 1).toDouble()).toLong()
            job.status = JobStatus.QUEUED
            job.runAfter = Instant.now().plusSeconds(delaySeconds)
        } else {
            job.status = JobStatus.FAILED
            job.finishedAt = Instant.now()
        }
        job.touch()
    }

    /** Osilib qolgan ishlar: pod qayta ishga tushsa RUNNING holida qolib ketadi. */
    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    fun requeueStuck() {
        if (!settings.workerEnabled()) return
        val moved = em.createNativeQuery(
            """
            update ai_jobs
            set status = case when attempts >= max_attempts then 'FAILED' else 'QUEUED' end,
                error_code = coalesce(error_code, 'STUCK'),
                locked_by = null,
                updated_at = now()
            where status = 'RUNNING' and locked_at < now() - interval '10 minutes'
            """.trimIndent(),
        ).executeUpdate()
        if (moved > 0) log.warnf("Osilib qolgan %d ta job navbatga qaytarildi", moved)
    }
}
