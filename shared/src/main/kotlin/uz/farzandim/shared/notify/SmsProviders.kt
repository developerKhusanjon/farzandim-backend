package uz.farzandim.shared.notify

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import uz.farzandim.shared.cache.ValkeyService
import uz.farzandim.shared.util.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Dev va CI uchun: hech narsa yubormaydi, faqat qayd qiladi. */
@ApplicationScoped
class LoggingSmsProvider : SmsProvider {

    private val log = Logger.getLogger(LoggingSmsProvider::class.java)

    override val name: String = "log"
    override val configured: Boolean = true

    override fun send(msisdn: String, text: String, messageId: String): SmsResult {
        log.infof(
            "SMS [%s] → %s (%d qism): %s",
            messageId, PhoneNumbers.mask(msisdn), SmsText.parts(text), text,
        )
        return SmsResult(accepted = true, providerMessageId = "log-$messageId")
    }
}

/**
 * eskiz.uz — O'zbekistondagi eng keng tarqalgan SMS shlyuzi.
 *
 * Autentifikatsiya: email+parol bilan token olinadi (~30 kun amal qiladi).
 * Token Valkey'da saqlanadi — bir nechta pod bitta tokendan foydalansin,
 * har ishga tushishda qayta login qilmasin. Valkey yo'q bo'lsa xotiradagi
 * nusxa ishlatiladi.
 *
 * 401 kelsa token eskirgan: bir marta qayta login qilib, so'rov takrorlanadi.
 */
@ApplicationScoped
class EskizSmsProvider : SmsProvider {

    private val log = Logger.getLogger(EskizSmsProvider::class.java)

    @Inject lateinit var settings: SmsSettings
    @Inject lateinit var valkey: ValkeyService

    @Volatile private var cachedToken: String? = null

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    override val name: String = "eskiz"

    override val configured: Boolean
        get() = settings.eskiz().email().orElse("").isNotBlank() &&
            settings.eskiz().password().orElse("").isNotBlank()

    override fun send(msisdn: String, text: String, messageId: String): SmsResult {
        if (!configured) {
            return SmsResult(false, errorCode = "NOT_CONFIGURED", errorMessage = "eskiz email/parol berilmagan")
        }

        var token = token() ?: return SmsResult(false, errorCode = "AUTH_FAILED", errorMessage = "token olinmadi")
        var response = post(token, msisdn, text, messageId)

        if (response.statusCode() == 401) {
            // Token eskirgan — yangilab, bir marta qayta urinamiz.
            invalidateToken()
            token = token() ?: return SmsResult(false, errorCode = "AUTH_FAILED", errorMessage = "token yangilanmadi")
            response = post(token, msisdn, text, messageId)
        }

        val body = response.body().orEmpty()
        if (response.statusCode() !in 200..299) {
            log.warnf("Eskiz rad etdi (%d): %s", response.statusCode(), body.take(300))
            return SmsResult(
                accepted = false,
                errorCode = "HTTP_${response.statusCode()}",
                errorMessage = body.take(300),
            )
        }

        val node = runCatching { Json.node(body) }.getOrNull()
        return SmsResult(
            accepted = true,
            providerMessageId = node?.path("id")?.asText(null) ?: messageId,
        )
    }

    private fun post(token: String, msisdn: String, text: String, messageId: String): HttpResponse<String> {
        val fields = buildMap {
            put("mobile_phone", msisdn)
            put("message", text)
            put("from", settings.originator())
            put("user_sms_id", messageId)
            settings.callbackUrl().ifPresent { put("callback_url", it) }
        }
        val body = Multipart.build(fields)
        val request = HttpRequest.newBuilder(URI.create(settings.eskiz().url().trimEnd('/') + "/api/message/sms/send"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=${Multipart.BOUNDARY}")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun token(): String? {
        cachedToken?.let { return it }
        valkey.get(TOKEN_KEY)?.let {
            cachedToken = it
            return it
        }
        return login()
    }

    private fun login(): String? {
        val fields = mapOf(
            "email" to settings.eskiz().email().orElse(""),
            "password" to settings.eskiz().password().orElse(""),
        )
        return try {
            val request = HttpRequest.newBuilder(URI.create(settings.eskiz().url().trimEnd('/') + "/api/auth/login"))
                .header("Content-Type", "multipart/form-data; boundary=${Multipart.BOUNDARY}")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofByteArray(Multipart.build(fields)))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.errorf("Eskiz login muvaffaqiyatsiz (%d): %s", response.statusCode(), response.body().take(200))
                return null
            }
            val token = Json.node(response.body()).path("data").path("token").asText(null)
            if (token.isNullOrBlank()) {
                log.error("Eskiz javobida token yo'q")
                return null
            }
            cachedToken = token
            // Token ~30 kun yashaydi; 25 kunda yangilaymiz.
            valkey.set(TOKEN_KEY, token, Duration.ofDays(25))
            token
        } catch (e: Exception) {
            log.errorf(e, "Eskiz login xatosi")
            null
        }
    }

    private fun invalidateToken() {
        cachedToken = null
        valkey.delete(TOKEN_KEY)
    }

    private companion object {
        const val TOKEN_KEY = "sms:eskiz:token"
    }
}

/**
 * playmobile.uz (smsxabar broker API) — ikkinchi variant.
 *
 * Basic auth, JSON to'plam. Eskiz'dan farqli: token boshqaruvi yo'q,
 * lekin javobda xabar identifikatori qaytmaydi — biz yuborgan
 * `message-id` bo'yicha kuzatiladi.
 */
@ApplicationScoped
class PlayMobileSmsProvider : SmsProvider {

    private val log = Logger.getLogger(PlayMobileSmsProvider::class.java)

    @Inject lateinit var settings: SmsSettings

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    override val name: String = "playmobile"

    override val configured: Boolean
        get() = settings.playmobile().login().orElse("").isNotBlank() &&
            settings.playmobile().password().orElse("").isNotBlank()

    override fun send(msisdn: String, text: String, messageId: String): SmsResult {
        if (!configured) {
            return SmsResult(false, errorCode = "NOT_CONFIGURED", errorMessage = "playmobile login/parol berilmagan")
        }

        val payload = mapOf(
            "messages" to listOf(
                buildMap {
                    put("recipient", msisdn)
                    put("message-id", messageId)
                    put(
                        "sms",
                        mapOf(
                            "originator" to settings.originator(),
                            "content" to mapOf("text" to text),
                        ),
                    )
                    settings.callbackUrl().ifPresent { put("callback-url", it) }
                },
            ),
        )

        val credentials = java.util.Base64.getEncoder().encodeToString(
            "${settings.playmobile().login().orElse("")}:${settings.playmobile().password().orElse("")}"
                .toByteArray(Charsets.UTF_8),
        )

        return try {
            val request = HttpRequest.newBuilder(
                URI.create(settings.playmobile().url().trimEnd('/') + "/broker-api/send"),
            )
                .header("Authorization", "Basic $credentials")
                .header("Content-Type", "application/json; charset=UTF-8")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), Charsets.UTF_8))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() !in 200..299) {
                log.warnf("PlayMobile rad etdi (%d): %s", response.statusCode(), response.body().take(300))
                return SmsResult(
                    accepted = false,
                    errorCode = "HTTP_${response.statusCode()}",
                    errorMessage = response.body().take(300),
                )
            }
            SmsResult(accepted = true, providerMessageId = messageId)
        } catch (e: Exception) {
            log.errorf(e, "PlayMobile yuborishda xato")
            SmsResult(false, errorCode = "TRANSPORT_ERROR", errorMessage = e.message?.take(300))
        }
    }
}

/**
 * `multipart/form-data` tanasi.
 *
 * Eskiz API'si aynan shu formatni kutadi. Kutubxona qo'shmaymiz — bu yerda
 * faqat oddiy matn maydonlari bor, fayl yuklanmaydi.
 */
internal object Multipart {
    const val BOUNDARY = "farzandim-sms-boundary"

    fun build(fields: Map<String, String>): ByteArray {
        val builder = StringBuilder()
        fields.forEach { (key, value) ->
            builder.append("--").append(BOUNDARY).append("\r\n")
            builder.append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
            builder.append(value).append("\r\n")
        }
        builder.append("--").append(BOUNDARY).append("--\r\n")
        return builder.toString().toByteArray(Charsets.UTF_8)
    }
}
