package uz.farzandim.shared.notify

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.inject.Inject
import org.jboss.logging.Logger
import uz.farzandim.shared.domain.Locale
import uz.farzandim.shared.domain.MessagePurpose
import uz.farzandim.shared.domain.DeliveryChannel
import uz.farzandim.shared.repo.SchoolRepository
import java.util.UUID

/**
 * Login va parolni SMS bilan yuborish.
 *
 * Standart holatda **o'chiq** (`farzandim.sms.send-credentials=false`):
 * maktab hisob ma'lumotlarini qog'ozda beradi va bu ko'pincha xavfsizroq —
 * parol SMS'da ochiq ketadi va telefonda saqlanib qoladi.
 *
 * Yoqilganda ham parol faqat SMS matnida bo'ladi: `sms_messages` jadvalida
 * matn saqlanmaydi, faqat "kimga, qachon, qaysi maqsadda" qoladi.
 */
@ApplicationScoped
class CredentialsNotifier {

    private val log = Logger.getLogger(CredentialsNotifier::class.java)

    @Inject lateinit var settings: SmsSettings
    @Inject lateinit var emailSettings: EmailSettings
    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var smsEvents: Event<SmsRequested>
    @Inject lateinit var emailEvents: Event<EmailRequested>

    /**
     * Telefon ham, email ham bo'lsa — ikkalasiga yuboriladi: maktab qaysi
     * kanal ota-onaga yetib borishini bilmaydi. Qaytadi: ishlatilgan kanallar.
     */
    fun send(
        phone: String?,
        username: String,
        password: String,
        schoolId: UUID?,
        userId: UUID?,
        locale: Locale = Locale.uz,
        email: String? = null,
    ): List<DeliveryChannel> {
        if (!settings.sendCredentials()) return emptyList()

        val schoolName = schoolId?.let { schools.findById(it)?.shortName } ?: "Farzandim AI"
        val used = mutableListOf<DeliveryChannel>()

        if (!phone.isNullOrBlank()) {
            smsEvents.fire(
                SmsRequested(
                    SmsRequest(
                        recipient = phone,
                        text = SmsTemplates.credentials(schoolName, username, password, locale),
                        purpose = MessagePurpose.CREDENTIALS,
                        schoolId = schoolId,
                        userId = userId,
                    ),
                ),
            )
            used += DeliveryChannel.SMS
            log.debugf("Hisob ma'lumotlari SMS navbatiga qo'yildi: %s", PhoneNumbers.mask(phone))
        }

        if (!email.isNullOrBlank() && EmailAddresses.isValid(email)) {
            emailEvents.fire(
                EmailRequested(
                    EmailRequest(
                        recipient = email,
                        subject = EmailTemplates.credentialsSubject(schoolName, locale),
                        html = EmailTemplates.credentialsHtml(
                            schoolName, username, password, locale, emailSettings.signature(),
                        ),
                        text = EmailTemplates.credentialsText(schoolName, username, password, locale),
                        purpose = MessagePurpose.CREDENTIALS,
                        schoolId = schoolId,
                        userId = userId,
                    ),
                ),
            )
            used += DeliveryChannel.EMAIL
            log.debugf("Hisob ma'lumotlari email navbatiga qo'yildi: %s", EmailAddresses.mask(email))
        }

        return used
    }
}
