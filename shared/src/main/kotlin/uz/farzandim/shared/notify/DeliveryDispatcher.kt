package uz.farzandim.shared.notify

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Tranzaksiya muvaffaqiyatli yakunlangach yuboriladigan hodisalar. */
data class SmsRequested(val request: SmsRequest)

data class PushRequested(
    val userIds: List<UUID>,
    val message: PushMessage,
)

data class EmailRequested(val request: EmailRequest)

/**
 * Tashqi kanallarga yuborish — tranzaksiyadan KEYIN va so'rov oqimidan TASHQARIDA.
 *
 * Ikki sabab:
 *
 * 1. **Tranzaksiya ichida yubormaslik.** Agar baho qo'yish tranzaksiyasi
 *    keyinroq qaytarib yuborilsa (rollback), ota-onaga "yangi baho" degan
 *    push allaqachon ketgan bo'lardi — ma'lumot esa bazada yo'q.
 *    `AFTER_SUCCESS` buni imkonsiz qiladi: commit bo'lmasa, yuborilmaydi.
 *
 * 2. **So'rovni ushlab turmaslik.** 100 ta ota-onaga push — 100 ta HTTP
 *    so'rov. Ularni javob oldidan bajarish o'qituvchining "Yuborish"
 *    tugmasini 30 soniyaga muzlatib qo'yardi. Ish alohida oqimga o'tadi.
 *
 * Yuborish muvaffaqiyatsiz bo'lsa ham bazadagi bildirishnoma qoladi —
 * ota-ona ilovani ochganda ko'radi. Push — qulaylik, yagona kanal emas.
 */
@ApplicationScoped
class DeliveryDispatcher {

    private val log = Logger.getLogger(DeliveryDispatcher::class.java)

    @Inject lateinit var pushService: PushService
    @Inject lateinit var smsService: SmsService
    @Inject lateinit var emailService: EmailService

    private lateinit var executor: ExecutorService

    @PostConstruct
    fun init() {
        val counter = AtomicInteger()
        executor = Executors.newFixedThreadPool(
            4,
            ThreadFactory { runnable ->
                Thread(runnable, "farzandim-delivery-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
    }

    @PreDestroy
    fun close() {
        executor.shutdown()
    }

    fun onPush(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: PushRequested) {
        if (event.userIds.isEmpty()) return
        executor.submit {
            runCatching { pushService.sendToUsers(event.userIds, event.message, executor) }
                .onFailure { log.errorf(it, "Push yuborishda kutilmagan xato") }
        }
    }

    fun onSms(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: SmsRequested) {
        executor.submit {
            runCatching { smsService.send(event.request) }
                .onFailure { log.errorf(it, "SMS yuborishda kutilmagan xato") }
        }
    }

    fun onEmail(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: EmailRequested) {
        executor.submit {
            runCatching { emailService.send(event.request) }
                .onFailure { log.errorf(it, "Email yuborishda kutilmagan xato") }
        }
    }
}
