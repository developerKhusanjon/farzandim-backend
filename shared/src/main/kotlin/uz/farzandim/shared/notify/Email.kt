package uz.farzandim.shared.notify

import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import uz.farzandim.shared.domain.Locale
import uz.farzandim.shared.domain.MessagePurpose
import java.util.Optional
import java.util.UUID

/**
 * Email sozlamalari.
 *
 * SMTP ulanishi Quarkus Mailer'niki (`quarkus.mailer.*`) — g'ildirakni
 * qaytadan ixtiro qilmaymiz. Bu yerda faqat bizning qarorlarimiz:
 * qaysi provayder, kimdan, va chegaralar.
 */
@ConfigMapping(prefix = "farzandim.email")
interface EmailSettings {
    /** log | smtp */
    @WithDefault("log")
    fun provider(): String

    /** Bo'sh bo'lsa `quarkus.mailer.from` ishlatiladi. */
    fun from(): Optional<String>

    fun replyTo(): Optional<String>

    /** Bitta manzilga sutkasiga nechta xat — spam va abuse himoyasi. */
    @WithDefault("30")
    fun dailyLimitPerAddress(): Int

    /** Xatlar oxiridagi imzo. */
    @WithDefault("Farzandim AI")
    fun signature(): String
}

data class EmailRequest(
    val recipient: String,
    val subject: String,
    val html: String,
    val text: String,
    val purpose: MessagePurpose,
    val schoolId: UUID? = null,
    val userId: UUID? = null,
)

data class EmailResult(
    val accepted: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

interface EmailProvider {
    val name: String
    val configured: Boolean
    fun send(request: EmailRequest): EmailResult
}

@ApplicationScoped
class LoggingEmailProvider : EmailProvider {

    private val log = Logger.getLogger(LoggingEmailProvider::class.java)

    override val name: String = "log"
    override val configured: Boolean = true

    override fun send(request: EmailRequest): EmailResult {
        log.infof(
            "EMAIL → %s | %s%n%s",
            EmailAddresses.mask(request.recipient), request.subject, request.text,
        )
        return EmailResult(accepted = true)
    }
}

/**
 * SMTP orqali yuborish (Quarkus Mailer).
 *
 * `configured` ning yagona mezoni — `quarkus.mailer.mock=false`.
 * Mock rejimida Quarkus xatni jo'natmay konsolga chiqaradi: dev uchun
 * qulay, lekin bazaga "yuborildi" deb yozib qo'yish yolg'on bo'lardi.
 *
 * Host bo'yicha tekshirmaymiz: uning standart qiymati `localhost` va
 * bo'sh qoldirib bo'lmaydi (SmallRye bo'sh satrni null deb hisoblaydi va
 * servis umuman ishga tushmaydi). Ya'ni email'ni yoqish ongli uch qadam:
 * `EMAIL_PROVIDER=smtp`, `MAIL_HOST=...`, `MAIL_MOCK=false`.
 */
@ApplicationScoped
class SmtpEmailProvider : EmailProvider {

    private val log = Logger.getLogger(SmtpEmailProvider::class.java)

    @Inject lateinit var mailer: Mailer
    @Inject lateinit var settings: EmailSettings

    @ConfigProperty(name = "quarkus.mailer.host", defaultValue = "")
    lateinit var host: String

    @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "true")
    var mock: Boolean = true

    override val name: String = "smtp"

    override val configured: Boolean get() = !mock

    override fun send(request: EmailRequest): EmailResult = try {
        log.debugf("SMTP: %s → %s", host, EmailAddresses.mask(request.recipient))
        val mail = Mail.withHtml(request.recipient, request.subject, request.html)
            .setText(request.text)
        settings.from().ifPresent { mail.from = it }
        settings.replyTo().ifPresent { mail.replyTo = it }

        mailer.send(mail)
        EmailResult(accepted = true)
    } catch (e: Exception) {
        log.warnf(e, "Email yuborilmadi: %s", EmailAddresses.mask(request.recipient))
        EmailResult(
            accepted = false,
            errorCode = e::class.simpleName ?: "SMTP_ERROR",
            errorMessage = e.message?.take(300),
        )
    }
}

object EmailAddresses {

    private val PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun normalize(raw: String): String = raw.trim().lowercase()

    fun isValid(raw: String): Boolean = PATTERN.matches(normalize(raw))

    /** Logda va admin panelda to'liq manzil ko'rinmasin. */
    fun mask(raw: String): String {
        val value = normalize(raw)
        val at = value.indexOf('@')
        if (at <= 0) return "***"
        val name = value.substring(0, at)
        val domain = value.substring(at)
        val visible = if (name.length <= 2) name.take(1) else name.take(2)
        return "$visible***$domain"
    }
}

/**
 * Xat shablonlari.
 *
 * Har biri ikki ko'rinishda: HTML (ko'pchilik mijozlar) va oddiy matn
 * (HTML o'chirilgan mijozlar va spam filtrlari uchun — faqat HTML bo'lgan
 * xat ko'pincha spam deb belgilanadi).
 */
object EmailTemplates {

    private const val BRAND = "#2F6BFF"
    private const val INK = "#12294B"
    private const val MUTED = "#5B6B85"
    private const val BG = "#F4F8FF"
    private const val BORDER = "#E6EDF8"

    fun otpSubject(locale: Locale): String = when (locale) {
        Locale.ru -> "Farzandim AI: код для входа"
        Locale.en -> "Farzandim AI: your sign-in code"
        else -> "Farzandim AI: kirish kodi"
    }

    fun otpText(code: String, locale: Locale, minutes: Int): String = when (locale) {
        Locale.ru -> """
            Код для входа в Farzandim AI: $code

            Действует $minutes минут. Никому не сообщайте этот код.
            Если вы не запрашивали вход — просто игнорируйте это письмо.
        """.trimIndent()
        Locale.en -> """
            Your Farzandim AI sign-in code: $code

            It is valid for $minutes minutes. Do not share it with anyone.
            If you did not request it, you can ignore this email.
        """.trimIndent()
        else -> """
            Farzandim AI ilovasiga kirish kodi: $code

            Kod $minutes daqiqa amal qiladi. Uni hech kimga aytmang.
            Agar siz kirishni so'ramagan bo'lsangiz, bu xatga e'tibor bermang.
        """.trimIndent()
    }

    fun otpHtml(code: String, locale: Locale, minutes: Int, signature: String): String {
        val (heading, note, warning) = when (locale) {
            Locale.ru -> Triple(
                "Код для входа",
                "Действует $minutes минут.",
                "Никому не сообщайте этот код. Если вы не запрашивали вход — игнорируйте письмо.",
            )
            Locale.en -> Triple(
                "Your sign-in code",
                "Valid for $minutes minutes.",
                "Do not share this code. If you did not request it, ignore this email.",
            )
            else -> Triple(
                "Kirish kodi",
                "Kod $minutes daqiqa amal qiladi.",
                "Kodni hech kimga aytmang. Kirishni so'ramagan bo'lsangiz, xatga e'tibor bermang.",
            )
        }
        return layout(
            heading = heading,
            body = """
                <div style="margin:24px 0;padding:20px;background:$BG;border:1px solid $BORDER;border-radius:12px;text-align:center">
                  <div style="font-size:34px;font-weight:700;letter-spacing:8px;color:$INK;font-family:monospace">$code</div>
                  <div style="margin-top:8px;font-size:13px;color:$MUTED">$note</div>
                </div>
                <p style="margin:0;font-size:13px;color:$MUTED">$warning</p>
            """.trimIndent(),
            signature = signature,
        )
    }

    fun credentialsSubject(schoolName: String, locale: Locale): String = when (locale) {
        Locale.ru -> "$schoolName: доступ к Farzandim AI"
        Locale.en -> "$schoolName: your Farzandim AI access"
        else -> "$schoolName: Farzandim AI ilovasiga kirish"
    }

    fun credentialsText(schoolName: String, username: String, password: String, locale: Locale): String =
        when (locale) {
            Locale.ru -> """
                $schoolName открыл для вас доступ к Farzandim AI.

                Логин:  $username
                Пароль: $password

                Смените пароль сразу после первого входа.
            """.trimIndent()
            Locale.en -> """
                $schoolName has created your Farzandim AI account.

                Login:    $username
                Password: $password

                Please change the password right after your first sign-in.
            """.trimIndent()
            else -> """
                $schoolName siz uchun Farzandim AI hisobini ochdi.

                Login: $username
                Parol: $password

                Birinchi kirishdan keyin parolni darhol o'zgartiring.
            """.trimIndent()
        }

    fun credentialsHtml(
        schoolName: String,
        username: String,
        password: String,
        locale: Locale,
        signature: String,
    ): String {
        val (heading, loginLabel, passwordLabel, note) = when (locale) {
            Locale.ru -> listOf(
                "$schoolName открыл вам доступ", "Логин", "Пароль",
                "Смените пароль сразу после первого входа.",
            )
            Locale.en -> listOf(
                "$schoolName created your account", "Login", "Password",
                "Please change the password right after your first sign-in.",
            )
            else -> listOf(
                "$schoolName siz uchun hisob ochdi", "Login", "Parol",
                "Birinchi kirishdan keyin parolni darhol o'zgartiring.",
            )
        }
        return layout(
            heading = heading,
            body = """
                <table role="presentation" style="width:100%;margin:24px 0;border-collapse:separate;border-spacing:0 8px">
                  <tr>
                    <td style="padding:12px 16px;background:$BG;border:1px solid $BORDER;border-radius:10px">
                      <div style="font-size:12px;color:$MUTED">$loginLabel</div>
                      <div style="font-size:18px;font-weight:600;color:$INK;font-family:monospace">$username</div>
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:12px 16px;background:$BG;border:1px solid $BORDER;border-radius:10px">
                      <div style="font-size:12px;color:$MUTED">$passwordLabel</div>
                      <div style="font-size:18px;font-weight:600;color:$INK;font-family:monospace">$password</div>
                    </td>
                  </tr>
                </table>
                <p style="margin:0;font-size:13px;color:$MUTED">$note</p>
            """.trimIndent(),
            signature = signature,
        )
    }

    /**
     * Barcha xatlar uchun umumiy qobiq. Tashqi rasm va shrift yuklanmaydi.
     *
     * Shablon oldindan `trimIndent()` qilinadi va faqat keyin to'ldiriladi:
     * ko'p qatorli qiymatni `"""..."""` ichiga qo'yib, keyin `trimIndent()`
     * chaqirish umumiy otstupni buzadi (qo'yilgan matnning o'z otstupi 0
     * bo'lgani uchun hech narsa kesilmaydi).
     */
    private fun layout(heading: String, body: String, signature: String): String =
        LAYOUT.replace("{{heading}}", heading)
            .replace("{{body}}", body)
            .replace("{{signature}}", signature)

    private val LAYOUT: String = """
        <!DOCTYPE html>
        <html lang="uz">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;padding:24px;background:$BG;font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif">
          <table role="presentation" style="max-width:520px;margin:0 auto;background:#FFFFFF;border:1px solid $BORDER;border-radius:16px;border-collapse:separate">
            <tr>
              <td style="padding:28px 28px 24px">
                <div style="font-size:15px;font-weight:700;color:$BRAND;letter-spacing:.3px">Farzandim AI</div>
                <h1 style="margin:14px 0 0;font-size:20px;line-height:1.35;color:$INK;font-weight:650">{{heading}}</h1>
                {{body}}
                <hr style="margin:24px 0 14px;border:none;border-top:1px solid $BORDER">
                <div style="font-size:12px;color:$MUTED">{{signature}}</div>
              </td>
            </tr>
          </table>
        </body>
        </html>
    """.trimIndent()

}
