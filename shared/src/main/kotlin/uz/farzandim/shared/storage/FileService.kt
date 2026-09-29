package uz.farzandim.shared.storage

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.constraints.NotBlank
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.cache.RateLimiter
import uz.farzandim.shared.domain.FileObject
import uz.farzandim.shared.domain.FileStatus
import uz.farzandim.shared.repo.FileRepository
import uz.farzandim.shared.util.Uuids
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UploadRequest(
    @field:NotBlank val fileName: String = "",
    @field:NotBlank val contentType: String = "",
    val sizeBytes: Long = 0,
    /** AVATAR | HOMEWORK | LESSON_MATERIAL | SUBMISSION | REPORT | GENERAL */
    val purpose: String = "GENERAL",
    val entityType: String? = null,
    val entityId: UUID? = null,
)

data class UploadTicket(
    val fileId: UUID,
    val uploadUrl: String,
    val method: String,
    val objectKey: String,
    val expiresInSeconds: Long,
    val maxBytes: Long,
)

data class FileDto(
    val id: UUID,
    val name: String,
    val contentType: String,
    val sizeBytes: Long,
    val purpose: String,
    val status: FileStatus,
    val createdAt: Instant,
    val url: String? = null,
)

/**
 * Fayl oqimi (docs/07-backend-architecture.md):
 *
 *   Brauzer → Quarkus:  yuklash uchun URL so'raydi
 *   Quarkus → Brauzer:  presigned Spaces URL (qisqa muddatli)
 *   Brauzer → Spaces:   faylni to'g'ridan-to'g'ri yuklaydi
 *   Brauzer → Quarkus:  "yukladim" deb tasdiqlaydi
 *   Quarkus → Spaces:   HEAD bilan tekshiradi (hajm, tur)
 *   Quarkus → Postgres: metama'lumotni READY qiladi
 *
 * Fayl baytlari hech qachon backend orqali o'tmaydi — bu Droplet
 * trafigi va xotirasini tejaydi va yuklashni tezlashtiradi.
 */
@ApplicationScoped
class FileService {

    private val log = Logger.getLogger(FileService::class.java)

    @Inject lateinit var files: FileRepository
    @Inject lateinit var spaces: SpacesClient
    @Inject lateinit var settings: SpacesSettings
    @Inject lateinit var rateLimiter: RateLimiter

    /** Fon tozalash ishlari shu servisda bajarilsinmi (faqat bitta servisda — school-backend). */
    @ConfigProperty(name = "farzandim.maintenance.enabled", defaultValue = "false")
    var maintenanceEnabled: Boolean = false

    private val allowedContentTypes = setOf(
        "image/jpeg", "image/png", "image/webp", "image/heic", "image/gif",
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "audio/mpeg", "audio/mp4", "audio/ogg",
        "video/mp4", "video/quicktime",
    )

    @Transactional
    fun requestUpload(request: UploadRequest, schoolId: UUID?, ownerId: UUID): UploadTicket {
        if (!spaces.configured) {
            throw ApiException(
                uz.farzandim.shared.api.ErrorCode.STORAGE_ERROR,
                "Fayl saqlash sozlanmagan (farzandim.spaces.*)",
            )
        }
        rateLimiter.check("upload", ownerId.toString(), 60, Duration.ofMinutes(5))

        val contentType = request.contentType.substringBefore(';').trim().lowercase()
        if (contentType !in allowedContentTypes) {
            throw ApiException.validation(
                "Bu fayl turi qo'llab-quvvatlanmaydi",
                mapOf("contentType" to contentType),
            )
        }
        if (request.sizeBytes > settings.maxFileBytes()) {
            throw ApiException.validation(
                "Fayl juda katta (maksimum ${settings.maxFileBytes() / 1024 / 1024} MB)",
                mapOf("sizeBytes" to request.sizeBytes.toString()),
            )
        }

        val fileId = Uuids.v7()
        val objectKey = buildObjectKey(schoolId, request.purpose, fileId, request.fileName)

        val entity = FileObject().apply {
            this.id = fileId
            this.schoolId = schoolId
            this.ownerId = ownerId
            this.bucket = spaces.bucket
            this.objectKey = objectKey
            this.originalName = sanitizeName(request.fileName)
            this.contentType = contentType
            this.sizeBytes = request.sizeBytes
            this.purpose = request.purpose
            this.entityType = request.entityType
            this.entityId = request.entityId
            this.status = FileStatus.PENDING
        }
        files.persist(entity)

        return UploadTicket(
            fileId = fileId,
            uploadUrl = spaces.presignPut(objectKey),
            method = "PUT",
            objectKey = objectKey,
            expiresInSeconds = settings.uploadUrlTtlSeconds(),
            maxBytes = settings.maxFileBytes(),
        )
    }

    /** Yuklashdan keyin: Spaces'dan haqiqiy hajmni olib, metama'lumotni yakunlaydi. */
    @Transactional
    fun confirmUpload(fileId: UUID, schoolId: UUID?, ownerId: UUID): FileDto {
        val file = requireAccess(fileId, schoolId, ownerId)
        if (file.status == FileStatus.READY) return toDto(file)

        val head = spaces.head(file.objectKey)
        if (!head.exists) {
            throw ApiException.validation("Fayl Spaces'da topilmadi — yuklash tugallanmagan")
        }
        if (head.contentLength > settings.maxFileBytes()) {
            spaces.delete(file.objectKey)
            file.status = FileStatus.FAILED
            throw ApiException.validation("Fayl ruxsat etilgan hajmdan katta")
        }

        file.sizeBytes = head.contentLength
        head.contentType?.let { file.contentType = it.substringBefore(';').trim() }
        file.checksum = head.etag
        file.status = FileStatus.READY
        file.confirmedAt = Instant.now()
        return toDto(file)
    }

    /** Yuklab olish: avval huquq, keyin qisqa muddatli URL. */
    @Transactional
    fun downloadUrl(fileId: UUID, schoolId: UUID?, userId: UUID): String {
        val file = requireAccess(fileId, schoolId, userId)
        if (file.status != FileStatus.READY) throw ApiException.notFound("Fayl", fileId)
        return spaces.presignGet(file.objectKey, downloadName = file.originalName)
    }

    @Transactional
    fun delete(fileId: UUID, schoolId: UUID?, userId: UUID) {
        val file = requireAccess(fileId, schoolId, userId)
        file.status = FileStatus.DELETED
        file.deletedAt = Instant.now()
        spaces.delete(file.objectKey)
    }

    fun listForEntity(entityType: String, entityId: UUID): List<FileDto> =
        files.listForEntity(entityType, entityId).map { toDto(it) }

    /**
     * Egalik tekshiruvi shu yerda — kontrollerda emas.
     * Maktab faylini boshqa maktab foydalanuvchisi so'rasa, 404 qaytadi.
     */
    private fun requireAccess(fileId: UUID, schoolId: UUID?, userId: UUID): FileObject {
        val file = files.findById(fileId) ?: throw ApiException.notFound("Fayl", fileId)
        val sameSchool = file.schoolId != null && file.schoolId == schoolId
        val isOwner = file.ownerId == userId
        if (!sameSchool && !isOwner) throw ApiException.notFound("Fayl", fileId)
        if (file.status == FileStatus.DELETED) throw ApiException.notFound("Fayl", fileId)
        return file
    }

    private fun buildObjectKey(schoolId: UUID?, purpose: String, fileId: UUID, fileName: String): String {
        val today = LocalDate.now()
        val tenant = schoolId?.toString() ?: "public"
        val safe = sanitizeName(fileName).takeLast(60)
        return "$tenant/${purpose.lowercase()}/${today.year}/%02d/$fileId-$safe".format(today.monthValue)
    }

    private fun sanitizeName(name: String): String =
        name.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "file" }
            .take(120)

    private fun toDto(file: FileObject) = FileDto(
        id = file.id,
        name = file.originalName,
        contentType = file.contentType,
        sizeBytes = file.sizeBytes,
        purpose = file.purpose,
        status = file.status,
        createdAt = file.createdAt,
    )

    /**
     * Tasdiqlanmagan yuklamalar: foydalanuvchi URL oldi-yu, yuklamadi.
     * Metama'lumot bazada osilib qolmasin.
     *
     * Faqat `farzandim.maintenance.enabled=true` bo'lgan servisda ishlaydi
     * (school-backend). Aks holda uchala servis har soatda bir xil ishni
     * takrorlardi, Spaces o'chiq servis (admin) esa bucket'da bor faylni ham
     * "topilmadi" deb FAILED qilib qo'yardi.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    fun cleanupStaleUploads() {
        if (!maintenanceEnabled || !spaces.configured) return
        val stale = files.listStale(Instant.now().minus(Duration.ofHours(6)), 500)
        if (stale.isEmpty()) return
        var removed = 0
        for (file in stale) {
            val head = runCatching { spaces.head(file.objectKey) }.getOrNull()
            if (head?.exists == true) {
                file.sizeBytes = head.contentLength
                file.status = FileStatus.READY
                file.confirmedAt = Instant.now()
            } else {
                file.status = FileStatus.FAILED
                removed++
            }
        }
        log.infof("Tasdiqlanmagan yuklamalar tozalandi: %d ta tekshirildi, %d ta bekor qilindi", stale.size, removed)
    }
}
