package uz.farzandim.shared.util

import io.quarkus.narayana.jta.QuarkusTransaction
import java.util.concurrent.Callable

/**
 * Qisqa, mustaqil tranzaksiya.
 *
 * Qoida: tashqi chaqiruv (Claude, SMS shlyuzi, Spaces) **hech qachon** DB
 * tranzaksiyasi ichida bajarilmaydi. Tranzaksiya ochiq turgan har bir soniya
 * hovuzdan bitta ulanishni (PgBouncer ortida — serverdagi ulanishni ham)
 * band qilib turadi: 20 soniyalik AI javobi paytida 8 ta o'qituvchi "AI xulosa"
 * tugmasini bossa, 8 ta ulanishli hovuz tugaydi va butun CRM kutib qoladi.
 *
 * Shuning uchun AI endpointlari uch bosqichda yoziladi:
 *
 *   1. `Tx.new { ... }` — tekshiruv, o'qish, prompt tayyorlash
 *   2. AI chaqiruvi — tranzaksiyasiz
 *   3. `Tx.new { ... }` — natijani yozish (obyekt id bo'yicha qayta o'qiladi)
 *
 * Birinchi tranzaksiyada o'qilgan entity'lar commit'dan keyin ajralib qoladi
 * (detached) — uchinchi bosqichda ularni o'zgartirish bazaga yozilmaydi.
 */
object Tx {
    fun <T> new(block: () -> T): T = QuarkusTransaction.requiringNew().call(Callable { block() })
}
