package uz.farzandim.shared.notify

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

/**
 * FCM HTTP v1 uchun OAuth2 JWT assertion'i o'zimiz yozgan kod bilan
 * imzolanadi. Xato imzo faqat Google 401 qaytarganda bilinadi — shuning
 * uchun bu yerda kalit juftligi yaratiladi, assertion imzolanadi va
 * ochiq kalit bilan tekshiriladi.
 */
class GoogleOAuthTest {

    private val mapper = ObjectMapper()
    private val oauth = GoogleOAuth()

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val pem: String = buildString {
        append("-----BEGIN PRIVATE KEY-----\n")
        append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded))
        append("\n-----END PRIVATE KEY-----\n")
    }

    private fun account(key: String = pem) = GoogleOAuth.ServiceAccount(
        projectId = "farzandim-test",
        clientEmail = "push@farzandim-test.iam.gserviceaccount.com",
        privateKeyPem = key,
    )

    @Test
    fun `assertion is a verifiable RS256 jwt`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val assertion = oauth.buildAssertion(account(), SCOPE, now)

        val parts = assertion.split('.')
        assertEquals(3, parts.size, "JWT uchta qismdan iborat bo'lishi kerak")

        val decoder = Base64.getUrlDecoder()
        val header = mapper.readTree(decoder.decode(parts[0]))
        assertEquals("RS256", header.path("alg").asText())
        assertEquals("JWT", header.path("typ").asText())

        val claims = mapper.readTree(decoder.decode(parts[1]))
        assertEquals("push@farzandim-test.iam.gserviceaccount.com", claims.path("iss").asText())
        assertEquals(SCOPE, claims.path("scope").asText())
        assertEquals("https://oauth2.googleapis.com/token", claims.path("aud").asText())
        assertEquals(now.epochSecond, claims.path("iat").asLong())
        assertEquals(now.epochSecond + 3600, claims.path("exp").asLong())

        val verified = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
        }.verify(decoder.decode(parts[2]))
        assertTrue(verified, "imzo ochiq kalit bilan tasdiqlanmadi")
    }

    /**
     * Muhit o'zgaruvchisida kalit bitta qatorda `\n` ketma-ketligi bilan
     * keladi — bu eng ko'p uchraydigan "nega ishlamayapti" holati.
     */
    @Test
    fun `private key with escaped newlines is accepted`() {
        val escaped = pem.replace("\n", "\\n")
        val parsed = oauth.parsePrivateKey(escaped)
        assertEquals(keyPair.private.algorithm, parsed.algorithm)
        assertTrue(parsed.encoded.contentEquals(keyPair.private.encoded))
    }

    @Test
    fun `service account json is parsed`() {
        val json = mapper.writeValueAsString(
            mapOf(
                "type" to "service_account",
                "project_id" to "farzandim-prod",
                "client_email" to "fcm@farzandim-prod.iam.gserviceaccount.com",
                "private_key" to pem,
                "token_uri" to "https://oauth2.googleapis.com/token",
            ),
        )
        val parsed = oauth.fromJson(json)
        assertEquals("farzandim-prod", parsed?.projectId)
        assertEquals("fcm@farzandim-prod.iam.gserviceaccount.com", parsed?.clientEmail)
        assertTrue(parsed?.privateKeyPem?.contains("BEGIN PRIVATE KEY") == true)
    }

    @Test
    fun `broken json yields null instead of throwing`() {
        assertEquals(null, oauth.fromJson("{ bu json emas"))
        assertEquals(null, oauth.fromJson("{}"))
    }

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    }
}
