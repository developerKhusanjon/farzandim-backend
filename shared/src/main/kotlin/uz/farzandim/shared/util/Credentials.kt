package uz.farzandim.shared.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * Login va parol generatsiyasi — maktab o'qituvchi va ota-onaga
 * qog'ozda yoki SMS orqali beradigan hisob ma'lumotlari.
 */
object Credentials {
    private val random = SecureRandom()

    /** Chalkashadigan belgilar yo'q: 0/O, 1/l/I — qog'ozdan ko'chirishda xato bo'lmasin. */
    private const val PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private const val TRANSLIT_FROM = "‘’'`"

    fun randomPassword(length: Int = 10): String =
        (1..length).map { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }.joinToString("")

    fun numericCode(length: Int = 6): String =
        (1..length).map { ('0' + random.nextInt(10)) }.joinToString("")

    fun randomToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * "Aliyev Sardor" → "sardor.aliyev". Band bo'lsa `taken` true qaytaradi
     * va oxiriga raqam qo'shiladi.
     */
    fun suggestUsername(firstName: String, lastName: String, taken: (String) -> Boolean): String {
        val base = listOf(firstName, lastName)
            .joinToString(".") { part ->
                part.trim()
                    .filterNot { it in TRANSLIT_FROM }
                    .lowercase(Locale.forLanguageTag("uz"))
                    .map { ch -> LATIN_MAP[ch] ?: ch }
                    .joinToString("")
                    .filter { it.isLetterOrDigit() }
            }
            .trim('.')
            .ifBlank { "user" }

        if (!taken(base)) return base
        for (i in 2..999) {
            val candidate = "$base$i"
            if (!taken(candidate)) return candidate
        }
        return "$base${System.currentTimeMillis() % 100000}"
    }

    /** Kirill yozuvidagi ismlarni lotinga o'tkazish (login faqat ASCII bo'lsin). */
    private val LATIN_MAP: Map<Char, Char> = mapOf(
        'а' to 'a', 'б' to 'b', 'в' to 'v', 'г' to 'g', 'д' to 'd', 'е' to 'e', 'ё' to 'e',
        'ж' to 'j', 'з' to 'z', 'и' to 'i', 'й' to 'y', 'к' to 'k', 'л' to 'l', 'м' to 'm',
        'н' to 'n', 'о' to 'o', 'п' to 'p', 'р' to 'r', 'с' to 's', 'т' to 't', 'у' to 'u',
        'ф' to 'f', 'х' to 'h', 'ц' to 'c', 'ч' to 'c', 'ш' to 's', 'щ' to 's', 'ъ' to ' ',
        'ы' to 'i', 'ь' to ' ', 'э' to 'e', 'ю' to 'u', 'я' to 'a',
        'ў' to 'u', 'қ' to 'q', 'ғ' to 'g', 'ҳ' to 'h',
    )
}
