package uz.farzandim.shared.api

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * docs/03-api-contract.md — yagona javob qobig'i.
 *
 *   muvaffaqiyat: { "data": <T>, "meta": { page, size, total } }
 *   xato:         { "error": { code, message, fields } }
 *
 * Har uchala servis aynan shu shaklni qaytaradi — frontend MSW mock'i ham
 * shuni taqlid qiladi, shuning uchun og'ish qilinmaydi.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val data: T,
    val meta: PageMeta? = null,
) {
    companion object {
        fun <T> of(data: T): ApiResponse<T> = ApiResponse(data)

        fun <T> paged(items: List<T>, page: Int, size: Int, total: Long): ApiResponse<List<T>> =
            ApiResponse(items, PageMeta(page, size, total))

        fun <T> paged(page: Page<T>): ApiResponse<List<T>> =
            ApiResponse(page.items, PageMeta(page.page, page.size, page.total))
    }
}

data class PageMeta(
    val page: Int,
    val size: Int,
    val total: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiErrorBody(
    val code: String,
    val message: String,
    val fields: Map<String, String>? = null,
    val traceId: String? = null,
)

data class ApiErrorResponse(val error: ApiErrorBody)

/** Servis qatlamidan qaytadigan sahifalangan natija. */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), page, size, total)

    companion object {
        fun <T> empty(page: Int = 1, size: Int = 20): Page<T> = Page(emptyList(), page, size, 0)
    }
}
