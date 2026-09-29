package uz.farzandim.shared.ai

import com.fasterxml.jackson.databind.JsonNode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.ErrorCode
import uz.farzandim.shared.cache.CacheKeys
import uz.farzandim.shared.cache.RateLimiter
import uz.farzandim.shared.cache.ValkeyService
import uz.farzandim.shared.platform.PlanAccess
import uz.farzandim.shared.platform.PlanFeatures
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.util.Credentials
import uz.farzandim.shared.util.Json
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

data class AiResult(
    val text: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val fromCache: Boolean,
)

/**
 * Claude bilan ishlashning yagona nuqtasi.
 *
 * Bu yerda uchta narsa markazlashtirilgan:
 *   1. Kesh — bir xil so'rov ikki marta pul sarflamasin
 *   2. Limit — maktabning kunlik AI kvotasi va foydalanuvchi chastotasi
 *   3. Hisob — har bir chaqiruv `ai_usage_daily` ga yoziladi
 */
@ApplicationScoped
class AiService {

    private val log = Logger.getLogger(AiService::class.java)

    @Inject @RestClient lateinit var claude: ClaudeApi
    @Inject lateinit var settings: AiSettings
    @Inject lateinit var valkey: ValkeyService
    @Inject lateinit var usage: AiUsageService

    fun complete(
        feature: String,
        schoolId: UUID?,
        system: String,
        userMessage: String,
        deep: Boolean = false,
        maxTokens: Int? = null,
    ): AiResult {
        val apiKey = settings.apiKey().orElse("")
        if (!settings.enabled() || apiKey.isBlank()) {
            throw ApiException(ErrorCode.AI_UNAVAILABLE, "AI xizmati o'chirilgan yoki API kalit berilmagan")
        }

        val model = if (deep) settings.deepModel() else settings.defaultModel()
        val cacheKey = CacheKeys.aiAnswer(Credentials.sha256("$model|$system|$userMessage"))

        valkey.get(cacheKey)?.let { cached ->
            return AiResult(cached, model, 0, 0, fromCache = true)
        }

        val request = ClaudeRequest(
            model = model,
            max_tokens = maxTokens ?: settings.maxTokens(),
            system = system,
            temperature = settings.temperature(),
            messages = listOf(ClaudeMessage("user", userMessage)),
        )

        val response = try {
            claude.messages(apiKey, settings.apiVersion(), request)
        } catch (e: Exception) {
            log.errorf(e, "Claude chaqiruvi muvaffaqiyatsiz: feature=%s", feature)
            throw ApiException(ErrorCode.AI_UNAVAILABLE, "AI xizmati hozir javob bermayapti", cause = e)
        }

        val text = response.text
        if (text.isBlank()) {
            throw ApiException(ErrorCode.AI_UNAVAILABLE, "AI bo'sh javob qaytardi")
        }

        val inputTokens = response.usage?.input_tokens ?: 0
        val outputTokens = response.usage?.output_tokens ?: 0

        valkey.set(cacheKey, text, Duration.ofSeconds(settings.cacheTtlSeconds()))
        schoolId?.let { usage.record(it, feature, inputTokens, outputTokens) }

        return AiResult(text, response.model ?: model, inputTokens, outputTokens, fromCache = false)
    }

    /** JSON kutiladigan promptlar uchun — model matn qo'shib yuborsa ham ajratib oladi. */
    fun completeJson(
        feature: String,
        schoolId: UUID?,
        system: String,
        userMessage: String,
        deep: Boolean = false,
        maxTokens: Int? = null,
    ): Pair<JsonNode, AiResult> {
        val result = complete(feature, schoolId, system, userMessage, deep, maxTokens)
        val node = Json.extract(result.text)
            ?: throw ApiException(ErrorCode.AI_UNAVAILABLE, "AI javobini o'qib bo'lmadi")
        return node to result
    }
}

/**
 * Kunlik AI sarfini hisoblash va kvotani tekshirish.
 *
 * Kvota bazadan (ishonchli manba) o'qiladi, lekin tez yo'l uchun Valkey'da
 * ham hisoblagich yuritiladi — har so'rovda SUM qilib o'tirmaslik uchun.
 */
@ApplicationScoped
class AiUsageService {

    @Inject lateinit var em: EntityManager
    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var valkey: ValkeyService
    @Inject lateinit var rateLimiter: RateLimiter
    @Inject lateinit var settings: AiSettings
    @Inject lateinit var planAccess: PlanAccess

    /**
     * Endpoint boshida chaqiriladi.
     *
     *   1. Foydalanuvchi chastotasi (daqiqasiga)
     *   2. Tarif / maktab sozlamasi / bayroq — `PlanAccess` (403 FEATURE_NOT_AVAILABLE)
     *   3. Maktabning kunlik kvotasi (429 AI_QUOTA_EXCEEDED)
     *
     * `ai_daily_limit = 0` — "AI yo'q" degani, "cheksiz" emas. Ilgari `<= 0`
     * tekshiruvi o'tkazib yuborilardi va BASIC tarifi (limit 0) cheksiz AI olardi.
     */
    fun assertQuota(schoolId: UUID?, userId: UUID, feature: String = PlanFeatures.AI) {
        rateLimiter.check("ai", userId.toString(), settings.userRateLimitPerMinute(), Duration.ofMinutes(1))
        // Mustaqil ota-ona (school_id = null) — faqat chastota cheklovi.
        if (schoolId == null) return

        planAccess.assertAiAllowed(schoolId, feature)
        val school = schools.findById(schoolId) ?: return
        if (school.aiDailyLimit <= 0) {
            throw ApiException(ErrorCode.AI_QUOTA_EXCEEDED, "Maktab uchun AI limiti belgilanmagan")
        }

        val used = callsToday(schoolId)
        if (used >= school.aiDailyLimit) {
            throw ApiException(
                ErrorCode.AI_QUOTA_EXCEEDED,
                "Maktabning bugungi AI limiti tugadi (${school.aiDailyLimit}). Ertaga yangilanadi.",
            )
        }
    }

    fun callsToday(schoolId: UUID): Int {
        val key = "ai:calls:$schoolId:${LocalDate.now()}"
        valkey.get(key)?.toIntOrNull()?.let { return it }
        val total = (
            em.createQuery(
                "select coalesce(sum(u.calls), 0) from AiUsageDaily u where u.id.schoolId = :school and u.id.usageDate = :day",
                java.lang.Long::class.java,
            )
                .setParameter("school", schoolId)
                .setParameter("day", LocalDate.now())
                .singleResult
            ).toInt()
        valkey.set(key, total.toString(), Duration.ofMinutes(5))
        return total
    }

    @Transactional
    fun record(schoolId: UUID, feature: String, inputTokens: Int, outputTokens: Int) {
        val cost = inputTokens.toLong() * settings.inputPricePerMTokenMicros() / 1_000_000 +
            outputTokens.toLong() * settings.outputPricePerMTokenMicros() / 1_000_000

        // Bitta atomik UPSERT: "o'qib, keyin yozish" o'rniga. Aks holda bir vaqtda
        // tugagan AI so'rovlari kunning birinchi qatorini ikki marta yaratishga
        // urinib to'qnashardi (javob pulli bo'lsa ham foydalanuvchi 409 olardi),
        // mavjud qatorda esa ikkala so'rov bir xil sonni o'qib, bittasi yo'qolardi.
        em.createNativeQuery(
            """
            insert into ai_usage_daily (school_id, usage_date, feature, calls, input_tokens, output_tokens, cost_micros)
            values (:school, :day, :feature, 1, :input, :output, :cost)
            on conflict (school_id, usage_date, feature) do update set
                calls         = ai_usage_daily.calls + 1,
                input_tokens  = ai_usage_daily.input_tokens + excluded.input_tokens,
                output_tokens = ai_usage_daily.output_tokens + excluded.output_tokens,
                cost_micros   = ai_usage_daily.cost_micros + excluded.cost_micros
            """.trimIndent(),
        )
            .setParameter("school", schoolId)
            .setParameter("day", LocalDate.now())
            .setParameter("feature", feature)
            .setParameter("input", inputTokens.toLong())
            .setParameter("output", outputTokens.toLong())
            .setParameter("cost", cost)
            .executeUpdate()

        // Keshlangan kunlik hisob eskirdi — keyingi o'qishda qayta hisoblansin.
        valkey.delete("ai:calls:$schoolId:${LocalDate.now()}")
    }
}
