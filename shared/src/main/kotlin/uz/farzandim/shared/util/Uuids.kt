package uz.farzandim.shared.util

import java.security.SecureRandom
import java.util.UUID

/**
 * UUIDv7 — vaqt bo'yicha o'suvchi identifikator (RFC 9562).
 *
 * Nega v4 emas: tasodifiy UUID birlamchi kalit sifatida B-tree indeksini
 * parchalaydi — har insert tasodifiy sahifaga tushadi. v7 ning birinchi
 * 48 biti millisekunddagi vaqt, shuning uchun yozuvlar indeks oxiriga
 * ketma-ket tushadi. `grades`, `attendance`, `ai_messages` kabi tez o'sadigan
 * jadvallarda bu sezilarli farq beradi.
 *
 *   48 bit  unix_ts_ms
 *    4 bit  versiya (0111)
 *   12 bit  tasodifiy
 *    2 bit  variant (10)
 *   62 bit  tasodifiy
 */
object Uuids {
    private val random = SecureRandom()

    fun v7(): UUID {
        val timestamp = System.currentTimeMillis() and 0x0000_FFFF_FFFF_FFFFL
        val randA = random.nextInt(0x1000).toLong()          // 12 bit
        val randB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL  // 62 bit

        val msb = (timestamp shl 16) or (0x7L shl 12) or randA
        val lsb = (0b10L shl 62) or randB

        return UUID(msb, lsb)
    }
}
