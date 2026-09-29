package uz.farzandim.shared.cache

import java.util.UUID

/** Kesh kalitlari bitta joyda — invalidatsiya qilishda adashmaslik uchun. */
object CacheKeys {
    fun schoolStats(schoolId: UUID) = "school:$schoolId:stats"
    fun schoolPrefix(schoolId: UUID) = "school:$schoolId:"
    fun studentPerformance(studentId: UUID, period: String) = "student:$studentId:perf:$period"
    fun studentPrefix(studentId: UUID) = "student:$studentId:"
    fun parentChildren(userId: UUID) = "parent:$userId:children"
    fun parentPrefix(userId: UUID) = "parent:$userId:"
    fun aiAnswer(hash: String) = "ai:answer:$hash"
    fun featureFlags() = "platform:flags"
}
