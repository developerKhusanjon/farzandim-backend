package uz.farzandim.shared.security

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

/**
 * JWT sozlamalari.
 *
 * Access token qisqa (standart 15 daqiqa) — o'g'irlangan token uzoq yashamaydi.
 * Refresh token uzoq, lekin bazada (hash holida) va har ishlatilganda
 * almashtiriladi (rotation). Eski token qayta kelsa — o'g'irlangan deb
 * hisoblanadi va butun sessiya oilasi bekor qilinadi.
 */
@ConfigMapping(prefix = "farzandim.jwt")
interface JwtSettings {
    @WithDefault("https://api.farzandim.uz")
    fun issuer(): String

    /** Access token amal qilish muddati (soniya). */
    @WithDefault("900")
    fun accessTtlSeconds(): Long

    /** Refresh token amal qilish muddati (soniya) — standart 30 kun. */
    @WithDefault("2592000")
    fun refreshTtlSeconds(): Long

    /** Bu servis qabul qiladigan auditoriya: farzandim-school | -mobile | -admin */
    fun audience(): String
}

@ConfigMapping(prefix = "farzandim.auth")
interface AuthSettings {
    /** Bitta login uchun daqiqasiga necha urinish. */
    @WithDefault("10")
    fun loginAttemptsPerMinute(): Int

    /** OTP kod amal qilish muddati (soniya). */
    @WithDefault("300")
    fun otpTtlSeconds(): Int

    /** Bitta OTP kodga necha marta noto'g'ri javob berish mumkin. */
    @WithDefault("5")
    fun otpMaxAttempts(): Int

    /** Bitta raqamga soatiga nechta OTP yuborish mumkin. */
    @WithDefault("5")
    fun otpPerHour(): Int

    /** Dev rejimda OTP kodi javobda qaytariladi (SMS shlyuzi yo'q). */
    @WithDefault("false")
    fun otpEchoCode(): Boolean
}
