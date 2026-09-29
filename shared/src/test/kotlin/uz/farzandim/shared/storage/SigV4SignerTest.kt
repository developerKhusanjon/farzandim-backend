package uz.farzandim.shared.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * AWS hujjatlaridagi rasmiy test vektori
 * ("Example: signature calculation for presigned URL", GET examplebucket/test.txt).
 *
 * Imzo algoritmi o'zimizniki bo'lgani uchun uni mustaqil, ma'lum javobi bor
 * misolga solishtirish shart — aks holda xatolik faqat Spaces 403 qaytarganda
 * bilinadi.
 */
class SigV4SignerTest {

    private val credentials = SigV4Signer.Credentials(
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = "us-east-1",
        service = "s3",
    )

    private val fixedInstant: Instant =
        ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

    @Test
    fun `presigned GET matches the AWS reference signature`() {
        val url = SigV4Signer.presign(
            method = "GET",
            endpoint = "https://examplebucket.s3.amazonaws.com",
            objectKey = "test.txt",
            credentials = credentials,
            expiresSeconds = 86400,
            now = fixedInstant,
        )

        val signature = url.substringAfter("X-Amz-Signature=")
        assertEquals(
            "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
            signature,
        )
    }

    @Test
    fun `presigned url carries the required query parameters`() {
        val url = SigV4Signer.presign(
            method = "PUT",
            endpoint = "https://farzandim.fra1.digitaloceanspaces.com",
            objectKey = "school-1/homework/2026/09/file.pdf",
            credentials = credentials,
            expiresSeconds = 900,
            now = fixedInstant,
        )

        assertTrue(url.startsWith("https://farzandim.fra1.digitaloceanspaces.com/school-1/homework/2026/09/file.pdf?"))
        listOf(
            "X-Amz-Algorithm=AWS4-HMAC-SHA256",
            "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request",
            "X-Amz-Date=20130524T000000Z",
            "X-Amz-Expires=900",
            "X-Amz-SignedHeaders=host",
            "X-Amz-Signature=",
        ).forEach { assertTrue(url.contains(it), "URL'da topilmadi: $it\n$url") }
    }

    @Test
    fun `uri encoding follows RFC 3986 rather than form encoding`() {
        assertEquals("a%20b", SigV4Signer.uriEncode("a b"))
        assertEquals("~", SigV4Signer.uriEncode("~"))
        assertEquals("%2F", SigV4Signer.uriEncode("/"))
        assertEquals("%D0%B0", SigV4Signer.uriEncode("а"))
    }
}
