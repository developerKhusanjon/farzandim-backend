package uz.farzandim.shared.cache

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.keys.KeyCommands
import io.quarkus.redis.datasource.value.SetArgs
import io.quarkus.redis.datasource.value.ValueCommands
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import uz.farzandim.shared.util.Json
import java.time.Duration

/**
 * Valkey — faqat kesh, limit, lock va qisqa muddatli sessiya ma'lumoti uchun.
 *
 * Bu yerda YO'QOLSA BO'LMAYDIGAN hech narsa saqlanmaydi. Valkey qayta
 * ishga tushsa, tizim sekinlashadi — lekin hech qanday CRM ma'lumoti,
 * job yoki fayl metama'lumoti yo'qolmaydi. Ular PostgreSQL'da.
 *
 * Valkey mavjud bo'lmasa ham servis ishlashda davom etadi (kesh o'tkazib
 * yuboriladi, limit "ruxsat" deb hisoblanadi) — chunki chatdagi kesh
 * uzilishi butun maktabni to'xtatib qo'ymasligi kerak.
 */
@ApplicationScoped
class ValkeyService {

    private val log = Logger.getLogger(ValkeyService::class.java)

    @Inject
    lateinit var redis: RedisDataSource

    private lateinit var strings: ValueCommands<String, String>
    private lateinit var counters: ValueCommands<String, Long>
    private lateinit var keys: KeyCommands<String>

    @PostConstruct
    fun init() {
        strings = redis.value(String::class.java, String::class.java)
        counters = redis.value(String::class.java, Long::class.javaObjectType)
        keys = redis.key(String::class.java)
    }

    /* --------------------------------- Kesh -------------------------------- */

    fun get(key: String): String? = safe(null) { strings.get(key) }

    fun set(key: String, value: String, ttl: Duration) {
        safe(Unit) { strings.setex(key, ttl.seconds, value) }
    }

    fun delete(vararg key: String) {
        safe(Unit) { keys.del(*key) }
    }

    /** Keshdan o'qiydi; bo'lmasa `loader` ni chaqirib, natijani JSON sifatida yozadi. */
    fun <T : Any> cached(key: String, ttl: Duration, type: Class<T>, loader: () -> T): T {
        val raw = get(key)
        if (raw != null) {
            val hit = runCatching { Json.mapper.readValue(raw, type) }.getOrNull()
            if (hit != null) return hit
        }
        val value = loader()
        runCatching { set(key, Json.write(value), ttl) }
        return value
    }

    /** Prefiksga tegishli kalitlarni tozalash (maktab ma'lumoti o'zgarganda). */
    fun invalidatePrefix(prefix: String) {
        safe(Unit) {
            val matching = keys.keys("$prefix*")
            if (matching.isNotEmpty()) keys.del(*matching.toTypedArray())
        }
    }

    /* ------------------------------- Hisoblagich ---------------------------- */

    /**
     * Sirpanmaydigan oyna (fixed window) limiti.
     * Qaytadi: shu oynadagi joriy son. `limit` dan katta bo'lsa — rad etiladi.
     */
    fun increment(key: String, window: Duration): Long {
        return safe(0L) {
            val count = counters.incr(key)
            if (count == 1L) keys.expire(key, window)
            count
        }
    }

    fun ttlSeconds(key: String): Long = safe(0L) { keys.ttl(key).coerceAtLeast(0) }

    /* ---------------------------------- Lock -------------------------------- */

    /**
     * Oddiy taqsimlangan lock: `SET key token NX EX ttl`.
     *
     * Bu "mukammal" lock emas (Redlock emas) — maqsadi bir xil ishni ikki pod
     * bir vaqtda bajarib qo'ymasligi. Ish baribir idempotent bo'lishi kerak.
     */
    fun tryLock(name: String, ttl: Duration): String? {
        val token = java.util.UUID.randomUUID().toString()
        val key = "lock:$name"
        return safe(null) {
            strings.set(key, token, SetArgs().nx().ex(ttl))
            if (strings.get(key) == token) token else null
        }
    }

    fun unlock(name: String, token: String) {
        val key = "lock:$name"
        safe(Unit) {
            if (strings.get(key) == token) keys.del(key)
        }
    }

    fun <T> withLock(name: String, ttl: Duration, block: () -> T): T? {
        val token = tryLock(name, ttl) ?: return null
        return try {
            block()
        } finally {
            unlock(name, token)
        }
    }

    /** Valkey uzilsa — xatoni yutamiz va zaxira qiymat qaytaramiz. */
    fun <T> safe(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            log.debugf(e, "Valkey amali bajarilmadi, zaxira qiymat ishlatildi")
            fallback
        }
}

/** `valkey.cachedAs<SchoolStats>(key, ttl) { ... }` — tip parametri bilan qulay chaqiruv. */
inline fun <reified T : Any> ValkeyService.cachedAs(
    key: String,
    ttl: Duration,
    noinline loader: () -> T,
): T = cached(key, ttl, T::class.java, loader)
