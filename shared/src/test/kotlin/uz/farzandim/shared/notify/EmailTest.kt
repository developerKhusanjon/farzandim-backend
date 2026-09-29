package uz.farzandim.shared.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uz.farzandim.shared.domain.Locale

class EmailAddressesTest {

    @Test
    fun `addresses are normalised to lower case`() {
        assertEquals("ota.ona@pochta.uz", EmailAddresses.normalize("  Ota.Ona@Pochta.UZ  "))
    }

    @Test
    fun `validation accepts real addresses and rejects junk`() {
        listOf("a@b.uz", "ota.ona+maktab@gmail.com", "user_1@sub.domain.co")
            .forEach { assertTrue(EmailAddresses.isValid(it), "rad etildi: $it") }

        listOf("", "salom", "a@b", "a@@b.uz", "@pochta.uz", "ota ona@pochta.uz")
            .forEach { assertFalse(EmailAddresses.isValid(it), "qabul qilindi: $it") }
    }

    @Test
    fun `mask hides the mailbox but keeps the domain`() {
        assertEquals("ot***@pochta.uz", EmailAddresses.mask("ota.ona@pochta.uz"))
        assertEquals("a***@b.uz", EmailAddresses.mask("a@b.uz"))
        assertEquals("***", EmailAddresses.mask("salom"))
    }
}

class EmailTemplatesTest {

    private val signature = "Farzandim AI"

    @Test
    fun `otp templates carry the code in every language`() {
        Locale.entries.forEach { locale ->
            val text = EmailTemplates.otpText("482913", locale, 5)
            val html = EmailTemplates.otpHtml("482913", locale, 5, signature)

            assertTrue(text.contains("482913"), "matnda kod yo'q ($locale)")
            assertTrue(html.contains("482913"), "HTML'da kod yo'q ($locale)")
            assertTrue(EmailTemplates.otpSubject(locale).isNotBlank())
            assertTrue(text.length > 40, "matn ko'rinishi juda qisqa ($locale)")
        }
    }

    @Test
    fun `credentials templates carry login and password`() {
        Locale.entries.forEach { locale ->
            val text = EmailTemplates.credentialsText("zamonaviy", "sardor.aliyev", "abc12xyz", locale)
            val html = EmailTemplates.credentialsHtml("zamonaviy", "sardor.aliyev", "abc12xyz", locale, signature)

            listOf(text, html).forEach { body ->
                assertTrue(body.contains("sardor.aliyev"), "login yo'q ($locale)")
                assertTrue(body.contains("abc12xyz"), "parol yo'q ($locale)")
                assertTrue(body.contains("zamonaviy"), "maktab nomi yo'q ($locale)")
            }
            assertTrue(EmailTemplates.credentialsSubject("zamonaviy", locale).contains("zamonaviy"))
        }
    }

    /**
     * Tashqi rasm yoki shrift yuklanishi ikki muammo keltiradi: pochta mijozi
     * uni bloklaydi (xat buzilib ko'rinadi) va yuklanish fakti "xat ochildi"
     * signalini tashqariga chiqaradi. Barcha uslub inline bo'lishi kerak.
     */
    @Test
    fun `html has no external resources`() {
        val html = EmailTemplates.otpHtml("123456", Locale.uz, 5, signature)
        listOf("<img", "<script", "<link", "url(", "https://", "http://").forEach { forbidden ->
            assertFalse(html.contains(forbidden), "HTML'da tashqi resurs bor: $forbidden")
        }
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains(signature))
    }

    @Test
    fun `uzbek templates avoid the typographic apostrophe`() {
        // Pochta uchun narx muammo emas, lekin matn SMS shabloni bilan bir xil
        // uslubda bo'lsin — kodlash muammolari ham shundan kelib chiqadi.
        val text = EmailTemplates.credentialsText("zamonaviy", "a", "b", Locale.uz)
        assertFalse(text.contains("‘"))
        assertFalse(text.contains("’"))
    }
}
