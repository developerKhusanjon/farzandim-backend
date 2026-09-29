package uz.farzandim.shared.notify

import com.fasterxml.jackson.databind.JsonNode
import jakarta.annotation.security.PermitAll
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import uz.farzandim.shared.cache.RateLimiter
import uz.farzandim.shared.domain.DeliveryStatus
import uz.farzandim.shared.util.Json
import java.time.Duration

/**
 * SMS provayderining yetkazib berish hisoboti.
 *
 * Bu endpoint autentifikatsiyasiz — provayder token yubormaydi.
 * Himoya uchta qatlamda:
 *   1. ixtiyoriy maxfiy token (`farzandim.sms.callback-token`) URL'da;
 *   2. faqat biz yaratgan `provider_message_id` bo'yicha topilgan qator
 *      yangilanadi — begona identifikator hech narsa qilmaydi;
 *   3. faqat holat maydoni o'zgaradi, boshqa hech narsa.
 *
 * Provayderlarning javob shakli har xil, shuning uchun bir nechta
 * ko'rinish qabul qilinadi.
 */
@Path("/webhooks/sms")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
class SmsWebhookResource {

    private val log = Logger.getLogger(SmsWebhookResource::class.java)

    @Inject lateinit var smsLog: SmsLog
    @Inject lateinit var settings: SmsSettings
    @Inject lateinit var rateLimiter: RateLimiter

    @POST
    @Path("/{provider}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun json(
        @PathParam("provider") provider: String,
        @QueryParam("token") token: String?,
        payload: JsonNode?,
    ): Response {
        if (!authorized(token)) return Response.status(404).build()
        rateLimiter.check("sms-callback", provider, 600, Duration.ofMinutes(1))

        val updates = extract(payload)
        val applied = updates.count { (id, status, error) -> smsLog.delivered(provider, id, status, error) }
        log.debugf("SMS callback (%s): %d dan %d tasi qo'llandi", provider, updates.size, applied)
        return Response.ok(mapOf("applied" to applied)).build()
    }

    @POST
    @Path("/{provider}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun form(
        @PathParam("provider") provider: String,
        @QueryParam("token") token: String?,
        form: MultivaluedMap<String, String>,
    ): Response {
        if (!authorized(token)) return Response.status(404).build()
        rateLimiter.check("sms-callback", provider, 600, Duration.ofMinutes(1))

        val id = form.getFirst("user_sms_id") ?: form.getFirst("message_id") ?: form.getFirst("id")
        val status = mapStatus(form.getFirst("status"))
        val applied = id != null && smsLog.delivered(provider, id, status, form.getFirst("error"))
        return Response.ok(mapOf("applied" to if (applied) 1 else 0)).build()
    }

    private fun authorized(token: String?): Boolean {
        val expected = settings.callbackToken().orElse("")
        return expected.isBlank() || expected == token
    }

    /**
     * Qo'llab-quvvatlanadigan shakllar:
     *   eskiz      { "message_id": "...", "user_sms_id": "...", "status": "DELIVRD" }
     *   playmobile { "messages": [ { "message-id": "...", "status": "Delivered" } ] }
     */
    private fun extract(payload: JsonNode?): List<Triple<String, DeliveryStatus, String?>> {
        if (payload == null || payload.isNull) return emptyList()

        val messages = payload.path("messages")
        if (messages.isArray) {
            return messages.mapNotNull { node ->
                val id = node.path("message-id").asText(null) ?: node.path("message_id").asText(null)
                    ?: return@mapNotNull null
                Triple(id, mapStatus(node.path("status").asText(null)), node.path("error").asText(null))
            }
        }

        val id = payload.path("user_sms_id").asText(null)
            ?: payload.path("message_id").asText(null)
            ?: payload.path("id").asText(null)
            ?: return emptyList()
        return listOf(Triple(id, mapStatus(payload.path("status").asText(null)), payload.path("error").asText(null)))
    }

    private fun mapStatus(raw: String?): DeliveryStatus = when (raw?.uppercase()?.trim()) {
        "DELIVRD", "DELIVERED", "SUCCESS", "OK" -> DeliveryStatus.DELIVERED
        "REJECTD", "REJECTED", "BLOCKED" -> DeliveryStatus.REJECTED
        "UNDELIV", "UNDELIVERABLE", "EXPIRED", "FAILED", "ERROR" -> DeliveryStatus.FAILED
        else -> DeliveryStatus.SENT
    }
}
