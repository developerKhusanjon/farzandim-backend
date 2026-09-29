package uz.farzandim.shared.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.domain.Locale

class PhoneNumbersTest {

    @Test
    fun `uzbek numbers normalise to a single form`() {
        val expected = "998901234567"
        listOf(
            "+998901234567",
            "998901234567",
            "+998 90 123 45 67",
            "998 (90) 123-45-67",
            "901234567",
            "90 123 45 67",
        ).forEach { input ->
            assertEquals(expected, PhoneNumbers.msisdn(input), "kiritilgan: $input")
        }
    }

    @Test
    fun `e164 keeps the plus prefix`() {
        assertEquals("+998901234567", PhoneNumbers.e164("90 123 45 67"))
    }

    @Test
    fun `foreign numbers pass through`() {
        assertEquals("77015550123", PhoneNumbers.msisdn("+7 701 555 01 23"))
    }

    @Test
    fun `too short numbers are rejected`() {
        assertThrows<ApiException> { PhoneNumbers.msisdn("12345") }
        assertThrows<ApiException> { PhoneNumbers.msisdn("") }
    }

    @Test
    fun `mask hides the middle of the number`() {
        val masked = PhoneNumbers.mask("+998901234567")
        assertFalse(masked.contains("1234"), "maskalangan raqamda o'rta qism qolmasin: $masked")
        assertTrue(masked.startsWith("99890"))
    }
}

class SmsTextTest {

    @Test
    fun `plain latin text stays in the cheap GSM-7 alphabet`() {
        val text = SmsTemplates.otp("123456", Locale.uz, 5)
        assertTrue(SmsText.isGsm7(text), "shablon UCS-2 ga o'tib ketdi: $text")
        assertEquals(1, SmsText.parts(text))
    }

    @Test
    fun `cyrillic forces UCS-2 where a part is only 70 characters`() {
        val russian = SmsTemplates.otp("123456", Locale.ru, 5)
        assertFalse(SmsText.isGsm7(russian), "kirill matn GSM-7 deb hisoblanmasin")

        // Ruscha OTP shabloni 70 belgiga sig'adi — ya'ni bitta qism, bitta narx.
        // Agar shablon uzaytirilsa, narx darhol ikki barobar bo'ladi.
        assertTrue(russian.length <= 70, "ruscha OTP bitta UCS-2 qismga sig'sin (${'$'}{russian.length} belgi)")
        assertEquals(1, SmsText.parts(russian))

        // Chegaradan oshgan kirill matn ikki qismga bo'linadi
        assertEquals(2, SmsText.parts("я".repeat(71)))
    }

    @Test
    fun `typographic apostrophe would double the price`() {
        // Aynan shu sabab shablonlarda oddiy ' ishlatiladi
        assertFalse(SmsText.isGsm7("O‘zbekiston"))
        assertTrue(SmsText.isGsm7("O'zbekiston"))
    }

    @Test
    fun `part boundaries follow the GSM spec`() {
        assertEquals(1, SmsText.parts("a".repeat(160)))
        assertEquals(2, SmsText.parts("a".repeat(161)))
        assertEquals(2, SmsText.parts("a".repeat(306)))
        assertEquals(3, SmsText.parts("a".repeat(307)))
    }

    @Test
    fun `credentials template carries both login and password`() {
        val text = SmsTemplates.credentials("zamonaviy", "sardor.aliyev", "abc12xyz", Locale.uz)
        assertTrue(text.contains("sardor.aliyev"))
        assertTrue(text.contains("abc12xyz"))
        assertTrue(SmsText.isGsm7(text))
    }
}

class MultipartTest {

    @Test
    fun `body contains every field between boundaries`() {
        val body = String(Multipart.build(mapOf("mobile_phone" to "998901234567", "message" to "salom")))
        assertTrue(body.contains("name=\"mobile_phone\""))
        assertTrue(body.contains("998901234567"))
        assertTrue(body.contains("name=\"message\""))
        assertTrue(body.contains("salom"))
        assertTrue(body.trimEnd().endsWith("--${Multipart.BOUNDARY}--"))
    }
}
