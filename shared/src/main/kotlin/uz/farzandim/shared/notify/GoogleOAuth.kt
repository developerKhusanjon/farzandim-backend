package uz.farzandim.shared.notify

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import uz.farzandim.shared.cache.ValkeyService
import uz.farzandim.shared.util.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Google Service Account → OAuth2 access token (JWT bearer oqimi).
 *
 * FCM HTTP v1 oddiy "server key" bilan ishlamaydi (u 2024-yilda o'chirildi) —
 * har so'rovga OAuth2 token kerak. Token olish uchun service account kaliti
 * bilan imzolangan JWT Google'ga yuboriladi.
 *
 * Nega google-auth-library emas: bizga butun kutubxonadan faqat shu bitta
 * oqim kerak — RS256 imzo va bitta HTTP so'rov. Kutubxona esa tranzitiv
 * bog'liqliklar keltiradi va native-image'da qo'shimcha sozlash talab qiladi.
 * SigV4 imzosi bilan bir xil yondashuv.
 */
@ApplicationScoped
class GoogleOAuth {

    private val log = Logger.getLogger(GoogleOAuth::class.java)

    @Inject lateinit var valkey: ValkeyService

    @Volatile private var cached: CachedToken? = null

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    private data class CachedToken(val value: String, val expiresAt: Instant)

    /** Service account ma'lumotlari — JSON fayldan yoki alohida o'zgaruvchilardan. */
    data class ServiceAccount(
        val projectId: String,
        val clientEmail: String,
        val privateKeyPem: String,
        val tokenUri: String = "https://oauth2.googleapis.com/token",
    )

    fun accessToken(account: ServiceAccount, scope: String): String? {
        cached?.let { if (it.expiresAt.isAfter(Instant.now().plusSeconds(60))) return it.value }

        val key = "push:google:token:${account.clientEmail}"
        valkey.get(key)?.let {
            cached = CachedToken(it, Instant.now().plusSeconds(valkey.ttlSeconds(key).coerceAtLeast(60)))
            return it
        }

        return fetch(account, scope)?.also { token ->
            // Google tokeni 1 soat yashaydi; 50 daqiqada yangilaymiz.
            cached = CachedToken(token, Instant.now().plusSeconds(3000))
            valkey.set(key, token, Duration.ofMinutes(50))
        }
    }

    private fun fetch(account: ServiceAccount, scope: String): String? = try {
        val assertion = buildAssertion(account, scope)
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8)

        val response = http.send(
            HttpRequest.newBuilder(URI.create(account.tokenUri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        if (response.statusCode() !in 200..299) {
            log.errorf("Google token olinmadi (%d): %s", response.statusCode(), response.body().take(300))
            null
        } else {
            Json.node(response.body()).path("access_token").asText(null)
        }
    } catch (e: Exception) {
        log.errorf(e, "Google OAuth xatosi")
        null
    }

    /** RS256 bilan imzolangan JWT: header.claims.signature */
    fun buildAssertion(account: ServiceAccount, scope: String, now: Instant = Instant.now()): String {
        val header = Json.write(mapOf("alg" to "RS256", "typ" to "JWT"))
        val claims = Json.write(
            mapOf(
                "iss" to account.clientEmail,
                "scope" to scope,
                "aud" to account.tokenUri,
                "iat" to now.epochSecond,
                "exp" to now.plusSeconds(3600).epochSecond,
            ),
        )

        val encoder = Base64.getUrlEncoder().withoutPadding()
        val signingInput = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8)) + "." +
            encoder.encodeToString(claims.toByteArray(StandardCharsets.UTF_8))

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(parsePrivateKey(account.privateKeyPem))
            update(signingInput.toByteArray(StandardCharsets.UTF_8))
        }.sign()

        return signingInput + "." + encoder.encodeToString(signature)
    }

    /**
     * PKCS#8 PEM → RSA kalit.
     *
     * Muhit o'zgaruvchisida kalit bitta qatorda `\n` ketma-ketligi bilan
     * keladi (JSON'dan ko'chirilganda) — uni haqiqiy qator uzilishiga
     * aylantiramiz, aks holda Base64 dekodlanmaydi.
     */
    fun parsePrivateKey(pem: String): java.security.PrivateKey {
        val normalized = pem.replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(normalized)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /** `service-account.json` faylidan o'qish. */
    fun fromJson(raw: String): ServiceAccount? {
        val node = runCatching { Json.node(raw) }.getOrNull() ?: return null
        val projectId = node.path("project_id").asText(null) ?: return null
        val clientEmail = node.path("client_email").asText(null) ?: return null
        val privateKey = node.path("private_key").asText(null) ?: return null
        return ServiceAccount(
            projectId = projectId,
            clientEmail = clientEmail,
            privateKeyPem = privateKey,
            tokenUri = node.path("token_uri").asText("https://oauth2.googleapis.com/token"),
        )
    }
}
