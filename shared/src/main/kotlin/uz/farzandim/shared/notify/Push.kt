package uz.farzandim.shared.notify

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import uz.farzandim.shared.util.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional

/**
 * Push sozlamalari.
 *
 * `provider`:
 *   log — logga yozadi (dev, CI)
 *   fcm — Firebase Cloud Messaging HTTP v1
 *
 * Kalitlar ikki yo'l bilan beriladi:
 *   1. `credentials-file` — service account JSON fayli (Droplet'dagi secret)
 *   2. alohida o'zgaruvchilar — App Platform secrets uchun qulay
 */
@ConfigMapping(prefix = "farzandim.push")
interface PushSettings {
    @WithDefault("log")
    fun provider(): String

    fun fcm(): Fcm

    interface Fcm {
        fun credentialsFile(): Optional<String>

        fun projectId(): Optional<String>

        fun clientEmail(): Optional<String>

        /** PKCS#8 PEM. Muhit o'zgaruvchisida `\n` ketma-ketligi bilan bo'lishi mumkin. */
        fun privateKey(): Optional<String>

        /** Android bildirishnoma kanali — ilovadagi kanal id bilan bir xil bo'lsin. */
        @WithDefault("farzandim-default")
        fun androidChannel(): String

        /** Bir vaqtda nechta qurilmaga yuborish. */
        @WithDefault("8")
        fun concurrency(): Int

        /**
         * Necha marta ketma-ket xato bo'lgach qurilma o'chiriladi.
         * FCM uzilishi butun bazani yo'q qilib yubormasligi uchun.
         */
        @WithDefault("5")
        fun maxFailures(): Int
    }
}

data class PushMessage(
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap(),
)

data class PushDelivery(
    val ok: Boolean,
    /** Token o'lik: ilova o'chirilgan yoki token bekor qilingan — darhol o'chiramiz. */
    val unregistered: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

interface PushProvider {
    val name: String
    val configured: Boolean
    fun send(deviceToken: String, message: PushMessage): PushDelivery
}

@ApplicationScoped
class LoggingPushProvider : PushProvider {

    private val log = Logger.getLogger(LoggingPushProvider::class.java)

    override val name: String = "log"
    override val configured: Boolean = true

    override fun send(deviceToken: String, message: PushMessage): PushDelivery {
        log.infof("PUSH → %s: %s — %s", deviceToken.take(12) + "…", message.title, message.body)
        return PushDelivery(ok = true)
    }
}

/**
 * Firebase Cloud Messaging HTTP v1.
 *
 * Eslatma: v1 da bitta so'rovda ko'p qurilmaga yuborish (multicast) yo'q —
 * eski `/batch` endpointi o'chirilgan. Shuning uchun har bir token uchun
 * alohida so'rov ketadi; `PushService` ularni cheklangan parallellikda
 * bajaradi.
 */
@ApplicationScoped
class FcmPushProvider : PushProvider {

    private val log = Logger.getLogger(FcmPushProvider::class.java)

    @Inject lateinit var settings: PushSettings
    @Inject lateinit var oauth: GoogleOAuth

    @Volatile private var account: GoogleOAuth.ServiceAccount? = null

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    override val name: String = "fcm"

    override val configured: Boolean get() = serviceAccount() != null

    private fun serviceAccount(): GoogleOAuth.ServiceAccount? {
        account?.let { return it }

        val fcm = settings.fcm()
        val fromFile = fcm.credentialsFile().orElse("").takeIf { it.isNotBlank() }?.let { path ->
            runCatching { oauth.fromJson(Files.readString(Path.of(path))) }
                .onFailure { log.errorf(it, "FCM kalit faylini o'qib bo'lmadi: %s", path) }
                .getOrNull()
        }

        val resolved = fromFile ?: run {
            val projectId = fcm.projectId().orElse("")
            val clientEmail = fcm.clientEmail().orElse("")
            val privateKey = fcm.privateKey().orElse("")
            if (projectId.isBlank() || clientEmail.isBlank() || privateKey.isBlank()) null
            else GoogleOAuth.ServiceAccount(projectId, clientEmail, privateKey)
        }

        account = resolved
        return resolved
    }

    override fun send(deviceToken: String, message: PushMessage): PushDelivery {
        val account = serviceAccount()
            ?: return PushDelivery(false, errorCode = "NOT_CONFIGURED", errorMessage = "FCM kaliti berilmagan")

        val token = oauth.accessToken(account, SCOPE)
            ?: return PushDelivery(false, errorCode = "AUTH_FAILED", errorMessage = "OAuth token olinmadi")

        val payload = mapOf(
            "message" to mapOf(
                "token" to deviceToken,
                "notification" to mapOf("title" to message.title, "body" to message.body),
                // data qiymatlari FCM'da faqat satr bo'lishi mumkin
                "data" to message.data.mapValues { it.value },
                "android" to mapOf(
                    "priority" to "high",
                    "notification" to mapOf(
                        "channel_id" to settings.fcm().androidChannel(),
                        "default_sound" to true,
                    ),
                ),
                "apns" to mapOf(
                    "headers" to mapOf("apns-priority" to "10"),
                    "payload" to mapOf("aps" to mapOf("sound" to "default", "badge" to 1)),
                ),
            ),
        )

        return try {
            val response = http.send(
                HttpRequest.newBuilder(
                    URI.create("https://fcm.googleapis.com/v1/projects/${account.projectId}/messages:send"),
                )
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), Charsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            if (response.statusCode() in 200..299) return PushDelivery(ok = true)

            val body = response.body().orEmpty()
            val status = Json.node(body).path("error").path("status").asText("")
            val unregistered = response.statusCode() == 404 ||
                status == "NOT_FOUND" || status == "UNREGISTERED" ||
                body.contains("UNREGISTERED") || body.contains("INVALID_ARGUMENT")

            if (!unregistered) {
                log.warnf("FCM xatosi (%d): %s", response.statusCode(), body.take(300))
            }
            PushDelivery(
                ok = false,
                unregistered = unregistered,
                errorCode = status.ifBlank { "HTTP_${response.statusCode()}" },
                errorMessage = body.take(300),
            )
        } catch (e: Exception) {
            log.warnf(e, "FCM yuborishda xato")
            PushDelivery(false, errorCode = "TRANSPORT_ERROR", errorMessage = e.message?.take(300))
        }
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    }
}
