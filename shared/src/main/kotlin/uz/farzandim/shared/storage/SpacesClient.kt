package uz.farzandim.shared.storage

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.ErrorCode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/**
 * DigitalOcean Spaces (S3 bilan mos).
 *
 * Bucket PRIVATE bo'ladi — hech qachon public-read emas. Brauzer faylni
 * to'g'ridan-to'g'ri Spaces'ga yuklaydi, lekin faqat backend bergan
 * qisqa muddatli presigned URL bilan. Yuklab olish ham shunday:
 * backend avval huquqni tekshiradi, keyin 5 daqiqalik URL beradi.
 */
@ConfigMapping(prefix = "farzandim.spaces")
interface SpacesSettings {
    /** `https://fra1.digitaloceanspaces.com` — bucketsiz. */
    fun endpoint(): String

    fun bucket(): String

    @WithDefault("fra1")
    fun region(): String

    /** Optional — kalitlarsiz muhitda servis ishga tushsin, Spaces esa o'chiq bo'lsin. */
    fun accessKey(): Optional<String>

    fun secretKey(): Optional<String>

    /** Yuklash uchun URL amal qilish muddati (soniya). */
    @WithDefault("900")
    fun uploadUrlTtlSeconds(): Long

    /** Yuklab olish uchun URL amal qilish muddati (soniya). */
    @WithDefault("300")
    fun downloadUrlTtlSeconds(): Long

    @WithDefault("26214400")
    fun maxFileBytes(): Long

    @WithDefault("true")
    fun enabled(): Boolean
}

data class ObjectHead(
    val exists: Boolean,
    val contentType: String?,
    val contentLength: Long,
    val etag: String?,
)

@ApplicationScoped
class SpacesClient {

    private val log = Logger.getLogger(SpacesClient::class.java)

    @Inject
    lateinit var settings: SpacesSettings

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    val bucket: String get() = settings.bucket()

    /** Kalitlar berilmagan bo'lsa Spaces amalda o'chiq. */
    val configured: Boolean
        get() = settings.enabled() &&
            settings.accessKey().orElse("").isNotBlank() &&
            settings.secretKey().orElse("").isNotBlank()

    /** Virtual-host uslubi: `https://<bucket>.<region>.digitaloceanspaces.com` */
    private val bucketEndpoint: String by lazy {
        val base = URI.create(settings.endpoint().trimEnd('/'))
        "${base.scheme}://${settings.bucket()}.${base.host}"
    }

    private val credentials: SigV4Signer.Credentials by lazy {
        SigV4Signer.Credentials(
            settings.accessKey().orElse(""),
            settings.secretKey().orElse(""),
            settings.region(),
        )
    }

    fun presignPut(objectKey: String, ttlSeconds: Long = settings.uploadUrlTtlSeconds()): String =
        SigV4Signer.presign("PUT", bucketEndpoint, objectKey, credentials, ttlSeconds)

    fun presignGet(
        objectKey: String,
        ttlSeconds: Long = settings.downloadUrlTtlSeconds(),
        downloadName: String? = null,
    ): String {
        val extra = downloadName?.let {
            mapOf("response-content-disposition" to "attachment; filename=\"${it.replace('"', '_')}\"")
        } ?: emptyMap()
        return SigV4Signer.presign("GET", bucketEndpoint, objectKey, credentials, ttlSeconds, extraQuery = extra)
    }

    /** Yuklama haqiqatan tushganini va hajmini tekshirish uchun. */
    fun head(objectKey: String): ObjectHead {
        if (!configured) return ObjectHead(false, null, 0, null)
        val url = SigV4Signer.presign("HEAD", bucketEndpoint, objectKey, credentials, 60)
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(15))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            if (response.statusCode() == 404) return ObjectHead(false, null, 0, null)
            if (response.statusCode() >= 300) {
                throw ApiException(ErrorCode.STORAGE_ERROR, "Spaces javobi: ${response.statusCode()}")
            }
            ObjectHead(
                exists = true,
                contentType = response.headers().firstValue("content-type").orElse(null),
                contentLength = response.headers().firstValueAsLong("content-length").orElse(0),
                etag = response.headers().firstValue("etag").orElse(null)?.trim('"'),
            )
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            log.warnf(e, "Spaces HEAD muvaffaqiyatsiz: %s", objectKey)
            throw ApiException(ErrorCode.STORAGE_ERROR, "Faylni tekshirib bo'lmadi", cause = e)
        }
    }

    fun delete(objectKey: String): Boolean {
        if (!configured) return false
        val url = SigV4Signer.presign("DELETE", bucketEndpoint, objectKey, credentials, 60)
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .DELETE()
                    .timeout(Duration.ofSeconds(15))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            response.statusCode() < 300 || response.statusCode() == 404
        } catch (e: Exception) {
            log.warnf(e, "Spaces DELETE muvaffaqiyatsiz: %s", objectKey)
            false
        }
    }
}
