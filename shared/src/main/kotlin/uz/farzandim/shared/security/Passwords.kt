package uz.farzandim.shared.security

import io.quarkus.elytron.security.common.BcryptUtil
import uz.farzandim.shared.api.ApiException

object Passwords {
    private const val MIN_LENGTH = 6

    fun hash(raw: String): String = BcryptUtil.bcryptHash(raw)

    fun matches(raw: String, hash: String?): Boolean =
        !hash.isNullOrBlank() && runCatching { BcryptUtil.matches(raw, hash) }.getOrDefault(false)

    fun validate(raw: String) {
        if (raw.length < MIN_LENGTH) {
            throw ApiException.validation(
                "Parol kamida $MIN_LENGTH belgidan iborat bo'lishi kerak",
                mapOf("password" to "kamida $MIN_LENGTH belgi"),
            )
        }
    }
}
