package uz.farzandim.shared.api

/**
 * `?page=1&size=20&sort=lastName,asc`
 *
 * Sort maydoni faqat oq ro'yxatdan olinadi — foydalanuvchi kiritgan matn
 * hech qachon to'g'ridan-to'g'ri JPQL'ga qo'yilmaydi (injection).
 */
data class PageRequest(
    val page: Int,
    val size: Int,
    val sortField: String?,
    val ascending: Boolean,
) {
    val offset: Int get() = (page - 1) * size

    /** Panache `Sort` uchun xavfsiz "field asc|desc" satri. */
    fun orderBy(allowed: Map<String, String>, default: String): String {
        val column = sortField?.let { allowed[it] } ?: default
        return "$column ${if (ascending) "asc" else "desc"}"
    }

    companion object {
        const val MAX_SIZE = 200

        fun of(page: Int?, size: Int?, sort: String?): PageRequest {
            val p = (page ?: 1).coerceAtLeast(1)
            val s = (size ?: 20).coerceIn(1, MAX_SIZE)
            if (sort.isNullOrBlank()) return PageRequest(p, s, null, true)
            val parts = sort.split(',')
            val field = parts[0].trim().ifBlank { null }
            val asc = parts.getOrNull(1)?.trim()?.lowercase() != "desc"
            return PageRequest(p, s, field, asc)
        }
    }
}
