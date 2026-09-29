package uz.farzandim.shared.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.util.Optional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Claude API (Anthropic Messages API).
 *
 * docs/05-ai-prompts.md: kundalik operatsiyalar — `claude-sonnet-5`,
 * chuqur tahlil — `claude-opus-5`.
 */
@ConfigMapping(prefix = "farzandim.ai")
interface AiSettings {
    @WithDefault("true")
    fun enabled(): Boolean

    /**
     * Optional — bo'sh satr SmallRye uchun "yo'q" degani; kalit berilmagan
     * muhitda (masalan CI yoki AI o'chirilgan dev) servis ishga tushishi kerak.
     */
    fun apiKey(): Optional<String>

    @WithDefault("2023-06-01")
    fun apiVersion(): String

    /** Kundalik ishlar: dars xulosasi, uy vazifasi, izoh. */
    @WithDefault("claude-sonnet-5")
    fun defaultModel(): String

    /** Chuqur tahlil: ko'nikma tahlili, kognitiv tavsiyalar. */
    @WithDefault("claude-opus-5")
    fun deepModel(): String

    @WithDefault("2000")
    fun maxTokens(): Int

    @WithDefault("0.4")
    fun temperature(): Double

    /** Bir xil so'rovga qayta murojaat qilmaslik uchun kesh muddati (soniya). */
    @WithDefault("86400")
    fun cacheTtlSeconds(): Long

    /** Foydalanuvchiga daqiqasiga nechta AI so'rovi. */
    @WithDefault("20")
    fun userRateLimitPerMinute(): Int

    /** So'rov kutilishi mumkin bo'lgan maksimal vaqt (sinxron endpointlar). */
    @WithDefault("25")
    fun syncWaitSeconds(): Long

    /** Job worker shu servisda ishlasinmi. */
    @WithDefault("false")
    fun workerEnabled(): Boolean

    @WithDefault("2")
    fun workerBatchSize(): Int

    /** Navbatni qanchalik tez-tez tekshirish (`@Scheduled` ifodasi). */
    @WithDefault("5s")
    fun workerPollInterval(): String

    /**
     * Narx — 1 million token uchun mikro-so'mda. Standart 0: operator
     * Anthropic narx sahifasidagi joriy qiymatni konfiguratsiyaga yozadi,
     * biz uni kodga qotirib qo'ymaymiz (narx o'zgaradi).
     */
    @WithDefault("0")
    fun inputPricePerMTokenMicros(): Long

    @WithDefault("0")
    fun outputPricePerMTokenMicros(): Long
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeMessage(
    val role: String,
    val content: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<ClaudeMessage>,
    val system: String? = null,
    val temperature: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeContentBlock(
    val type: String? = null,
    val text: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeUsage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ClaudeResponse(
    val id: String? = null,
    val model: String? = null,
    val stop_reason: String? = null,
    val content: List<ClaudeContentBlock> = emptyList(),
    val usage: ClaudeUsage? = null,
) {
    val text: String get() = content.filter { it.type == null || it.type == "text" }.mapNotNull { it.text }.joinToString("\n").trim()
}

@Path("/v1")
@RegisterRestClient(configKey = "claude")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ClaudeApi {

    @POST
    @Path("/messages")
    fun messages(
        @HeaderParam("x-api-key") apiKey: String,
        @HeaderParam("anthropic-version") version: String,
        request: ClaudeRequest,
    ): ClaudeResponse
}

/* ----------------------------- Embedding (Voyage) -------------------------- */

@ConfigMapping(prefix = "farzandim.embedding")
interface EmbeddingSettings {
    @WithDefault("false")
    fun enabled(): Boolean

    fun apiKey(): Optional<String>

    /** 1024 o'lcham — migratsiyadagi `vector(1024)` bilan mos bo'lishi shart. */
    @WithDefault("voyage-3")
    fun model(): String

    @WithDefault("1024")
    fun dimensions(): Int
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EmbeddingRequest(
    val input: List<String>,
    val model: String,
    val input_type: String? = "document",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbeddingItem(val embedding: List<Double> = emptyList(), val index: Int = 0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbeddingResponse(val data: List<EmbeddingItem> = emptyList(), val model: String? = null)

@Path("/v1")
@RegisterRestClient(configKey = "voyage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface EmbeddingApi {

    @POST
    @Path("/embeddings")
    fun embed(
        @HeaderParam("Authorization") authorization: String,
        request: EmbeddingRequest,
    ): EmbeddingResponse
}
