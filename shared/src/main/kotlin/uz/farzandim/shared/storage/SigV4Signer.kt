package uz.farzandim.shared.storage

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 — query-string (presigned URL) varianti.
 *
 * Nega AWS SDK emas: bizga S3'ning butun SDK'si kerak emas, faqat
 * presign. SDK native-image'da qo'shimcha konfiguratsiya va ~10 MB talab
 * qiladi; bu yerda esa bor-yo'g'i HMAC-SHA256 zanjiri. Spaces S3 bilan
 * mos, shuning uchun aynan shu imzo ishlaydi.
 *
 * Faqat `host` sarlavhasi imzolanadi — brauzer qo'shimcha sarlavha
 * yubormaydi, shuning uchun imzo buzilmaydi. Fayl turi va hajmi
 * yuklamadan keyin `HEAD` bilan tekshiriladi.
 */
object SigV4Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    private val AMZ_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    data class Credentials(val accessKey: String, val secretKey: String, val region: String, val service: String = "s3")

    /**
     * @param endpoint bucket bilan birga: `https://farzandim.fra1.digitaloceanspaces.com`
     * @param objectKey bucket ichidagi yo'l, boshida `/` siz
     * @param expiresSeconds 1..604800 (7 kun)
     */
    fun presign(
        method: String,
        endpoint: String,
        objectKey: String,
        credentials: Credentials,
        expiresSeconds: Long,
        now: Instant = Instant.now(),
        extraQuery: Map<String, String> = emptyMap(),
    ): String {
        val uri = URI.create(endpoint.trimEnd('/'))
        val host = uri.host + (if (uri.port > 0) ":${uri.port}" else "")
        val amzDate = AMZ_DATE.format(now)
        val dateStamp = DATE_STAMP.format(now)
        val scope = "$dateStamp/${credentials.region}/${credentials.service}/aws4_request"

        val canonicalUri = canonicalizePath(objectKey)

        val query = sortedMapOf<String, String>()
        query["X-Amz-Algorithm"] = ALGORITHM
        query["X-Amz-Credential"] = "${credentials.accessKey}/$scope"
        query["X-Amz-Date"] = amzDate
        query["X-Amz-Expires"] = expiresSeconds.coerceIn(1, 604_800).toString()
        query["X-Amz-SignedHeaders"] = "host"
        extraQuery.forEach { (k, v) -> query[k] = v }

        val canonicalQuery = query.entries.joinToString("&") { (k, v) -> "${uriEncode(k)}=${uriEncode(v)}" }
        val canonicalHeaders = "host:$host\n"
        val signedHeaders = "host"

        val canonicalRequest = listOf(
            method.uppercase(),
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            UNSIGNED_PAYLOAD,
        ).joinToString("\n")

        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            hex(sha256(canonicalRequest.toByteArray(Charsets.UTF_8))),
        ).joinToString("\n")

        val signature = hex(hmac(signingKey(credentials, dateStamp), stringToSign.toByteArray(Charsets.UTF_8)))

        return "${uri.scheme}://$host$canonicalUri?$canonicalQuery&X-Amz-Signature=$signature"
    }

    fun signingKey(credentials: Credentials, dateStamp: String): ByteArray {
        var key = hmac("AWS4${credentials.secretKey}".toByteArray(Charsets.UTF_8), dateStamp.toByteArray(Charsets.UTF_8))
        key = hmac(key, credentials.region.toByteArray(Charsets.UTF_8))
        key = hmac(key, credentials.service.toByteArray(Charsets.UTF_8))
        return hmac(key, "aws4_request".toByteArray(Charsets.UTF_8))
    }

    /** Har bir segment alohida kodlanadi, `/` saqlanadi. */
    private fun canonicalizePath(objectKey: String): String =
        "/" + objectKey.trimStart('/').split('/').joinToString("/") { uriEncode(it) }

    /**
     * RFC 3986 — AWS talab qiladigan qat'iy variant.
     * `URLEncoder` mos emas: bo'shliqni `+` ga aylantiradi va `~` ni kodlaydi.
     */
    fun uriEncode(value: String): String {
        val out = StringBuilder(value.length * 2)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
