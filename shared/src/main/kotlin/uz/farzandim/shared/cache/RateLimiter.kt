package uz.farzandim.shared.cache

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import uz.farzandim.shared.api.ApiException
import java.time.Duration

/**
 * So'rov chastotasi cheklovi (Valkey hisoblagichi ustida).
 *
 * Ishlatiladigan joylar: login (brute force), OTP yuborish (SMS puli),
 * AI endpointlari (model puli), fayl presign (Spaces so'rovi).
 */
@ApplicationScoped
class RateLimiter {

    @Inject
    lateinit var valkey: ValkeyService

    /** Limitdan oshsa `RATE_LIMITED` (429) tashlaydi. */
    fun check(bucket: String, identity: String, limit: Int, window: Duration) {
        val key = "rl:$bucket:$identity"
        val count = valkey.increment(key, window)
        if (count > limit) {
            val retryAfter = valkey.ttlSeconds(key).takeIf { it > 0 } ?: window.seconds
            throw ApiException.rateLimited(retryAfter)
        }
    }

    /** Istisno tashlamaydigan variant — chaqiruvchi o'zi qaror qiladi. */
    fun allow(bucket: String, identity: String, limit: Int, window: Duration): Boolean =
        valkey.increment("rl:$bucket:$identity", window) <= limit

    fun reset(bucket: String, identity: String) {
        valkey.delete("rl:$bucket:$identity")
    }
}
