package uz.farzandim.shared.notify

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Any
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import uz.farzandim.shared.domain.EmailMessage
import uz.farzandim.shared.domain.SmsMessage
import uz.farzandim.shared.domain.MessagePurpose
import uz.farzandim.shared.domain.DeliveryStatus
import uz.farzandim.shared.repo.DeviceTokenRepository
import uz.farzandim.shared.repo.EmailRepository
import uz.farzandim.shared.repo.SmsRepository
import uz.farzandim.shared.util.Uuids
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Qurilma tokenlarining "sog'lig'i".
 *
 * Alohida bean va alohida tranzaksiya: push yuborish fon oqimida, hech qanday
 * tranzaksiyasiz bajariladi — bu yerdagi har bir metod o'zi tranzaksiya ochadi.
 */
@ApplicationScoped
class DeviceTokenService {

    private val log = Logger.getLogger(DeviceTokenService::class.java)

    @Inject lateinit var devices: DeviceTokenRepository
    @Inject lateinit var settings: PushSettings

    @Transactional
    fun activeTokensFor(userIds: Collection<UUID>): List<String> =
        devices.listForUsers(userIds).map { it.token }

    @Transactional
    fun recordSuccess(token: String) {
        val device = devices.findByToken(token) ?: return
        if (device.failureCount != 0 || device.lastError != null) {
            device.failureCount = 0
            device.lastError = null
        }
        device.lastSeenAt = Instant.now()
    }

    /**
     * Xatoni qayd etish.
     *
     * `UNREGISTERED` — token o'lik, darhol o'chiriladi.
     * Boshqa xatolar vaqtinchalik bo'lishi mumkin (FCM uzilishi, tarmoq):
     * ketma-ket `maxFailures` martadan keyingina qurilma o'chiriladi.
     */
    @Transactional
    fun recordFailure(token: String, delivery: PushDelivery) {
        val device = devices.findByToken(token) ?: return

        if (delivery.unregistered) {
            devices.delete(device)
            log.debugf("O'lik push token o'chirildi: %s…", token.take(12))
            return
        }

        device.failureCount += 1
        device.lastError = delivery.errorCode
        if (device.failureCount >= settings.fcm().maxFailures()) {
            device.disabledAt = Instant.now()
            log.infof(
                "Qurilma o'chirildi (%d marta ketma-ket xato): %s…",
                device.failureCount, token.take(12),
            )
        }
    }
}

/**
 * Push yuborish.
 *
 * FCM HTTP v1 da multicast yo'q — har token uchun alohida so'rov. Ularni
 * ketma-ket yuborish 100 ta ota-ona uchun 30+ soniya degani, shuning uchun
 * cheklangan parallellikda bajariladi.
 */
@ApplicationScoped
class PushService {

    private val log = Logger.getLogger(PushService::class.java)

    @Inject @Any lateinit var providers: Instance<PushProvider>
    @Inject lateinit var settings: PushSettings
    @Inject lateinit var deviceTokens: DeviceTokenService

    val provider: PushProvider
        get() {
            val requested = settings.provider().lowercase()
            val found = providers.firstOrNull { it.name == requested }
            if (found == null) {
                log.warnf("Push provayderi topilmadi: «%s» — logga yoziladi", requested)
                return providers.first { it.name == "log" }
            }
            if (!found.configured) {
                log.warnf("Push provayderi «%s» sozlanmagan — logga yoziladi", requested)
                return providers.first { it.name == "log" }
            }
            return found
        }

    fun sendToUsers(userIds: Collection<UUID>, message: PushMessage, executor: Executor): Int {
        val tokens = deviceTokens.activeTokensFor(userIds.distinct())
        if (tokens.isEmpty()) return 0
        return send(tokens, message, executor)
    }

    fun send(tokens: List<String>, message: PushMessage, executor: Executor): Int {
        val active = provider
        val chunkSize = settings.fcm().concurrency().coerceAtLeast(1)
        var delivered = 0

        tokens.chunked(chunkSize).forEach { chunk ->
            val futures = chunk.map { token ->
                CompletableFuture.supplyAsync({ token to active.send(token, message) }, executor)
            }
            futures.forEach { future ->
                val (token, result) = runCatching { future.join() }.getOrNull() ?: return@forEach
                if (result.ok) {
                    delivered++
                    deviceTokens.recordSuccess(token)
                } else {
                    deviceTokens.recordFailure(token, result)
                }
            }
        }

        log.debugf("Push: %d/%d qurilmaga yetdi (%s)", delivered, tokens.size, active.name)
        return delivered
    }
}

/** SMS yozuvlari — alohida tranzaksiyada, chunki yuborish fon oqimida ketadi. */
@ApplicationScoped
class SmsLog {

    @Inject lateinit var repository: SmsRepository

    @Transactional
    fun queued(request: SmsRequest, provider: String, msisdn: String, parts: Int): UUID {
        val entity = SmsMessage().apply {
            this.id = Uuids.v7()
            this.schoolId = request.schoolId
            this.userId = request.userId
            this.provider = provider
            this.recipient = msisdn
            this.purpose = request.purpose
            this.parts = parts
            this.status = DeliveryStatus.QUEUED
        }
        repository.persist(entity)
        return entity.id
    }

    /**
     * Oxirgi sutkada shu raqamga nechta SMS ketgan.
     *
     * Repository'ga to'g'ridan-to'g'ri murojaat qilib bo'lmaydi: yuborish fon
     * oqimida, tranzaksiyasiz bajariladi va EntityManager kontekst talab qiladi.
     */
    @Transactional
    fun sentInLastDay(recipient: String): Long =
        repository.countSince(recipient, Instant.now().minus(Duration.ofDays(1)))

    @Transactional
    fun finished(id: UUID, result: SmsResult) {
        val entity = repository.findById(id) ?: return
        entity.status = if (result.accepted) DeliveryStatus.SENT else DeliveryStatus.FAILED
        entity.providerMessageId = result.providerMessageId
        entity.errorCode = result.errorCode
        entity.errorMessage = result.errorMessage
        if (result.accepted) entity.sentAt = Instant.now()
    }

    /** Provayder callback'i: yetkazib berish holati. */
    @Transactional
    fun delivered(provider: String, providerMessageId: String, status: DeliveryStatus, error: String?): Boolean {
        val entity = repository.findByProviderId(provider, providerMessageId) ?: return false
        entity.status = status
        if (status == DeliveryStatus.DELIVERED) entity.deliveredAt = Instant.now()
        if (error != null) entity.errorMessage = error.take(300)
        return true
    }
}

/**
 * SMS yuborish.
 *
 * Har bir yuborish `sms_messages` ga yoziladi — SMS pullik kanal va
 * "kimga nima ketdi, qancha turdi" degan savol javobsiz qolmasligi kerak.
 * Xabar matni saqlanmaydi (OTP kodi va parol shu matnda bo'ladi).
 */
@ApplicationScoped
class SmsService {

    private val log = Logger.getLogger(SmsService::class.java)

    @Inject @Any lateinit var providers: Instance<SmsProvider>
    @Inject lateinit var settings: SmsSettings
    @Inject lateinit var smsLog: SmsLog

    val provider: SmsProvider
        get() {
            val requested = settings.provider().lowercase()
            val found = providers.firstOrNull { it.name == requested }
            if (found == null) {
                log.warnf("SMS provayderi topilmadi: «%s» — logga yoziladi", requested)
                return providers.first { it.name == "log" }
            }
            if (!found.configured) {
                log.warnf("SMS provayderi «%s» sozlanmagan — logga yoziladi", requested)
                return providers.first { it.name == "log" }
            }
            return found
        }

    fun send(request: SmsRequest): SmsResult {
        val msisdn = runCatching { PhoneNumbers.msisdn(request.recipient) }.getOrElse {
            return SmsResult(false, errorCode = "INVALID_NUMBER", errorMessage = it.message)
        }

        // Sutkalik chegara — xarajat va spam himoyasi. Bazadagi haqiqiy
        // yozuvlar bo'yicha hisoblanadi, Valkey'siz ham ishonchli.
        val sentToday = smsLog.sentInLastDay(msisdn)
        if (sentToday >= settings.dailyLimitPerNumber()) {
            log.warnf("SMS chegarasi oshdi: %s (%d/sutka)", PhoneNumbers.mask(msisdn), sentToday)
            return SmsResult(false, errorCode = "DAILY_LIMIT", errorMessage = "Sutkalik SMS chegarasi tugadi")
        }

        val active = provider
        val parts = SmsText.parts(request.text)
        val id = smsLog.queued(request, active.name, msisdn, parts)

        val result = runCatching { active.send(msisdn, request.text, id.toString()) }
            .getOrElse { SmsResult(false, errorCode = "TRANSPORT_ERROR", errorMessage = it.message?.take(300)) }

        smsLog.finished(id, result)

        if (!result.accepted) {
            log.warnf(
                "SMS yuborilmadi (%s → %s): %s %s",
                active.name, PhoneNumbers.mask(msisdn), result.errorCode, result.errorMessage.orEmpty(),
            )
        }
        return result
    }

    fun otpEnabled(): Boolean = provider.name != "log" || settings.provider() == "log"

    val credentialsBySms: Boolean get() = settings.sendCredentials()
}

/** Email yozuvlari — SMS bilan bir xil sabab: yuborish fon oqimida ketadi. */
@ApplicationScoped
class EmailLog {

    @Inject lateinit var repository: EmailRepository

    @Transactional
    fun queued(request: EmailRequest, provider: String, recipient: String): UUID {
        val entity = EmailMessage().apply {
            this.id = Uuids.v7()
            this.schoolId = request.schoolId
            this.userId = request.userId
            this.provider = provider
            this.recipient = recipient
            this.subject = request.subject.take(300)
            this.purpose = request.purpose
            this.status = DeliveryStatus.QUEUED
        }
        repository.persist(entity)
        return entity.id
    }

    @Transactional
    fun finished(id: UUID, result: EmailResult) {
        val entity = repository.findById(id) ?: return
        entity.status = if (result.accepted) DeliveryStatus.SENT else DeliveryStatus.FAILED
        entity.errorCode = result.errorCode
        entity.errorMessage = result.errorMessage
        if (result.accepted) entity.sentAt = Instant.now()
    }

    @Transactional
    fun sentInLastDay(recipient: String): Long =
        repository.countSince(recipient, Instant.now().minus(Duration.ofDays(1)))
}

/**
 * Email yuborish.
 *
 * SMS bilan bir xil qoidalar: provayder tanlanadi, sozlanmagan bo'lsa
 * `log` ga tushadi, har bir yuborish bazaga yoziladi, xabar matni esa
 * saqlanmaydi (OTP kodi va parol shu matnda bo'ladi).
 */
@ApplicationScoped
class EmailService {

    private val log = Logger.getLogger(EmailService::class.java)

    @Inject @Any lateinit var providers: Instance<EmailProvider>
    @Inject lateinit var settings: EmailSettings
    @Inject lateinit var emailLog: EmailLog

    val provider: EmailProvider
        get() {
            val requested = settings.provider().lowercase()
            val found = providers.firstOrNull { it.name == requested }
            if (found == null) {
                log.warnf("Email provayderi topilmadi: «%s» — logga yoziladi", requested)
                return providers.first { it.name == "log" }
            }
            if (!found.configured) {
                log.warnf(
                    "Email provayderi «%s» sozlanmagan (quarkus.mailer.host / mock) — logga yoziladi",
                    requested,
                )
                return providers.first { it.name == "log" }
            }
            return found
        }

    fun send(request: EmailRequest): EmailResult {
        val recipient = EmailAddresses.normalize(request.recipient)
        if (!EmailAddresses.isValid(recipient)) {
            return EmailResult(false, errorCode = "INVALID_ADDRESS", errorMessage = "Email formati noto'g'ri")
        }

        val sentToday = emailLog.sentInLastDay(recipient)
        if (sentToday >= settings.dailyLimitPerAddress()) {
            log.warnf("Email chegarasi oshdi: %s (%d/sutka)", EmailAddresses.mask(recipient), sentToday)
            return EmailResult(false, errorCode = "DAILY_LIMIT", errorMessage = "Sutkalik email chegarasi tugadi")
        }

        val active = provider
        val id = emailLog.queued(request, active.name, recipient)

        val result = runCatching { active.send(request.copy(recipient = recipient)) }
            .getOrElse { EmailResult(false, errorCode = "TRANSPORT_ERROR", errorMessage = it.message?.take(300)) }

        emailLog.finished(id, result)

        if (!result.accepted) {
            log.warnf(
                "Email yuborilmadi (%s → %s): %s %s",
                active.name, EmailAddresses.mask(recipient), result.errorCode, result.errorMessage.orEmpty(),
            )
        }
        return result
    }

    val signature: String get() = settings.signature()
}
