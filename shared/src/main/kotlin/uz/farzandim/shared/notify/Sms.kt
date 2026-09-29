package uz.farzandim.shared.notify

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.Locale
import java.util.Optional

/**
 * SMS shlyuzi sozlamalari.
 *
 * `provider` qiymatiga qarab implementatsiya tanlanadi:
 *   log         — hech narsa yubormaydi, logga yozadi (dev, CI)
 *   eskiz       — eskiz.uz (O'zbekistonda eng keng tarqalgan)
 *   playmobile  — playmobile.uz (smsxabar broker API)
 */
@ConfigMapping(prefix = "farzandim.sms")
interface SmsSettings {
    @WithDefault("log")
    fun provider(): String

    /** Jo'natuvchi nomi (alfanumerik yoki qisqa raqam). Operator tasdiqlaydi. */
    @WithDefault("4546")
    fun originator(): String

    /** Bitta raqamga sutkasiga nechta SMS — xarajat va spam himoyasi. */
    @WithDefault("20")
    fun dailyLimitPerNumber(): Int

    /** Login/parolni SMS bilan yuborish (standart: yo'q, maktab qog'ozda beradi). */
    @WithDefault("false")
    fun sendCredentials(): Boolean

    /** Yetkazib berish holati uchun provayder chaqiradigan URL. */
    fun callbackUrl(): Optional<String>

    /**
     * Callback URL'idagi maxfiy token (`?token=...`).
     * Berilmasa — callback endpointi tokensiz ham qabul qiladi.
     */
    fun callbackToken(): Optional<String>

    fun eskiz(): Eskiz

    fun playmobile(): PlayMobile

    interface Eskiz {
        @WithDefault("https://notify.eskiz.uz")
        fun url(): String

        fun email(): Optional<String>

        fun password(): Optional<String>
    }

    interface PlayMobile {
        @WithDefault("https://send.smsxabar.uz")
        fun url(): String

        fun login(): Optional<String>

        fun password(): Optional<String>
    }
}

/**
 * O'zbekiston raqamlarini bitta ko'rinishga keltirish.
 *
 * Foydalanuvchi `+998 90 123 45 67`, `998901234567` yoki `901234567` deb
 * kiritishi mumkin — provayderlar esa `998901234567` kutadi. Normalizatsiya
 * bitta joyda bo'lmasa, bitta oqimda SMS ketadi, boshqasida "raqam topilmadi".
 */
object PhoneNumbers {

    private const val UZ_CODE = "998"
    private const val UZ_NATIONAL_LENGTH = 9

    /** `998901234567` — provayderlar uchun (plyussiz). */
    fun msisdn(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) {
            throw ApiException.validation("Telefon raqami bo'sh", mapOf("phone" to "to'ldiring"))
        }
        val normalized = when {
            digits.length == UZ_NATIONAL_LENGTH -> UZ_CODE + digits
            digits.startsWith(UZ_CODE) && digits.length == UZ_CODE.length + UZ_NATIONAL_LENGTH -> digits
            // Boshqa davlat raqami — o'zgartirmaymiz, lekin uzunligini tekshiramiz
            digits.length in 10..15 -> digits
            else -> throw ApiException.validation(
                "Telefon raqami noto'g'ri formatda",
                mapOf("phone" to "masalan +998 90 123 45 67"),
            )
        }
        return normalized
    }

    /** `+998901234567` — bazada va UI'da shu ko'rinish. */
    fun e164(raw: String): String = "+" + msisdn(raw)

    /** Logda to'liq raqam ko'rinmasin. */
    fun mask(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length <= 6) "***" else digits.take(5) + "***" + digits.takeLast(2)
    }
}

/**
 * SMS matni va uning narxi.
 *
 * Operator bitta xabar uchun emas, har bir "qism" uchun pul oladi.
 * GSM-7 alifbosida 160 belgi bir qismga sig'adi; agar matnda kirill yoki
 * "chiroyli" apostrof (') bo'lsa, butun xabar UCS-2 ga o'tadi va bir qism
 * atigi 70 belgi bo'lib qoladi — narx ikki-uch barobar oshadi.
 *
 * Shuning uchun shablonlarda faqat oddiy ASCII apostrof (') ishlatiladi.
 */
object SmsText {

    private const val GSM_BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXTENDED = "^{}\\[~]|€"

    fun isGsm7(text: String): Boolean =
        text.all { it in GSM_BASIC || it in GSM_EXTENDED }

    /** Xabar necha qismga bo'linadi (narx shunga bog'liq). */
    fun parts(text: String): Int {
        if (text.isEmpty()) return 1
        return if (isGsm7(text)) {
            val length = text.sumOf { if (it in GSM_EXTENDED) 2 else 1 }
            if (length <= 160) 1 else (length + 152) / 153
        } else {
            if (text.length <= 70) 1 else (text.length + 66) / 67
        }
    }
}

/** Xabar matnlari. Til foydalanuvchining `locale` maydonidan olinadi. */
object SmsTemplates {

    fun otp(code: String, locale: Locale, minutes: Int): String = when (locale) {
        Locale.ru -> "Farzandim AI: код входа $code. Действует $minutes мин. Никому не сообщайте."
        Locale.en -> "Farzandim AI: your login code is $code. Valid for $minutes min. Do not share it."
        else -> "Farzandim AI: kirish kodi $code. $minutes daqiqa amal qiladi. Hech kimga aytmang."
    }

    fun credentials(schoolName: String, username: String, password: String, locale: Locale): String =
        when (locale) {
            Locale.ru -> "$schoolName: вход в Farzandim AI. Логин: $username, пароль: $password. " +
                "Смените пароль после первого входа."
            Locale.en -> "$schoolName: Farzandim AI access. Login: $username, password: $password. " +
                "Please change it after first sign-in."
            else -> "$schoolName: Farzandim AI ilovasiga kirish. Login: $username, parol: $password. " +
                "Birinchi kirishdan keyin parolni o'zgartiring."
        }
}

data class SmsRequest(
    val recipient: String,
    val text: String,
    val purpose: uz.farzandim.shared.domain.MessagePurpose,
    val schoolId: java.util.UUID? = null,
    val userId: java.util.UUID? = null,
)

data class SmsResult(
    val accepted: Boolean,
    val providerMessageId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/**
 * SMS provayderi. Yangi operator qo'shish — shu interfeysni amalga oshirish
 * va `farzandim.sms.provider` ga nomini yozish.
 */
interface SmsProvider {
    val name: String

    /** Sozlamalar to'liq berilganmi (kalitlarsiz provayder tanlab bo'lmaydi). */
    val configured: Boolean

    fun send(msisdn: String, text: String, messageId: String): SmsResult
}
