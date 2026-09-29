package uz.farzandim.shared.platform

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import uz.farzandim.shared.api.ApiException
import uz.farzandim.shared.api.ErrorCode
import uz.farzandim.shared.domain.School
import uz.farzandim.shared.domain.SchoolStatus
import uz.farzandim.shared.repo.FeatureFlagRepository
import uz.farzandim.shared.repo.SchoolRepository
import uz.farzandim.shared.repo.SubscriptionPlanRepository
import uz.farzandim.shared.util.Json
import java.time.LocalDate
import java.util.UUID

/** `subscription_plans.features` kalitlari. */
object PlanFeatures {
    const val AI = "ai"
    const val AI_CHAT = "aiChat"
    const val AI_SKILL_ANALYSIS = "aiSkillAnalysis"
    const val VECTOR_SEARCH = "vectorSearch"
    const val MOBILE_APP = "mobileApp"
    const val REPORTS = "reports"

    val ALL = listOf(AI, AI_CHAT, AI_SKILL_ANALYSIS, VECTOR_SEARCH, MOBILE_APP, REPORTS)
}

/** `feature_flags.key` qiymatlari (V6 migratsiyasi). */
object FlagKeys {
    const val VECTOR_SEARCH = "ai.vector_search"
    const val AUTO_SUMMARY = "ai.auto_summary"
    const val PARENT_INDEPENDENT = "parent.independent"
    const val VIRUS_SCAN = "files.virus_scan"
}

/**
 * Platforma bayroqlari: maktab bo'yicha istisno bo'lsa — u, aks holda umumiy qiymat.
 * Bayroq bazada yo'q bo'lsa — o'chiq.
 */
@ApplicationScoped
class FeatureFlagService {

    @Inject lateinit var flags: FeatureFlagRepository

    fun isEnabled(key: String, schoolId: UUID?): Boolean {
        val flag = flags.findById(key) ?: return false
        if (schoolId != null) {
            val override = Json.obj(flag.schoolOverrides).get(schoolId.toString())
            if (override != null && override.isBoolean) return override.booleanValue()
        }
        return flag.enabled
    }
}

/**
 * Maktab nimaga haqli: tarif imkoniyatlari, maktab sozlamasi, bayroq, obuna.
 *
 * Super admin paneli tarifni o'zgartirganda uning ta'siri shu yerda
 * ko'rinadi — school-backend ham, mobile-backend ham AI chaqirishdan oldin
 * `AiUsageService.assertQuota` orqali shu tekshiruvdan o'tadi.
 */
@ApplicationScoped
class PlanAccess {

    @Inject lateinit var schools: SchoolRepository
    @Inject lateinit var plans: SubscriptionPlanRepository
    @Inject lateinit var flagService: FeatureFlagService
    @Inject lateinit var em: EntityManager

    /** Tarif imkoniyatlari — faqat mantiqiy qiymatlar; noma'lum kalit — `false`. */
    fun features(school: School): Map<String, Boolean> {
        val plan = plans.findById(school.subscriptionPlan.name)
        val raw = Json.obj(plan?.features)
        return PlanFeatures.ALL.associateWith { key -> raw.get(key)?.asBoolean(false) ?: false }
    }

    fun hasFeature(school: School, key: String): Boolean = features(school)[key] == true

    /** Tarif yozuvining o'zidan (maktab hali yaratilmagan paytda). */
    fun hasFeatureInPlan(featuresJson: String?, key: String): Boolean =
        Json.obj(featuresJson).get(key)?.asBoolean(false) ?: false

    /**
     * Maktab ishlay oladimi. Login'da ham, refresh'da ham chaqiriladi —
     * aks holda obunasi tugagan maktab refresh token yashagunicha
     * (CRM'da 30, mobilda 90 kun) ishlashda davom etardi.
     */
    fun assertSchoolOperational(school: School) {
        if (school.status != SchoolStatus.ACTIVE) {
            val reason = school.statusReason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            throw ApiException.forbidden("Maktab hisobi faol emas$reason")
        }
        val until = school.subscriptionUntil
        if (until != null && until.isBefore(LocalDate.now())) {
            throw ApiException(ErrorCode.SUBSCRIPTION_EXPIRED, "Maktab obunasi tugagan")
        }
    }

    /**
     * AI funksiyasi uchun ruxsat.
     *
     * Tartib muhim: avval tarif (sotuv masalasi), keyin maktab sozlamasi
     * (maktabning o'z qarori), keyin bayroq (platforma qarori). Foydalanuvchi
     * ko'radigan xabar aynan to'siq qayerdaligini aytadi.
     */
    fun assertAiAllowed(schoolId: UUID, feature: String = PlanFeatures.AI) {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        assertSchoolOperational(school)

        val features = features(school)
        if (features[PlanFeatures.AI] != true) {
            throw ApiException.featureNotAvailable(
                "AI funksiyalari «${school.subscriptionPlan.name}» tarifida mavjud emas",
            )
        }
        if (feature != PlanFeatures.AI && features[feature] != true) {
            throw ApiException.featureNotAvailable(
                "Bu AI funksiyasi «${school.subscriptionPlan.name}» tarifida mavjud emas",
            )
        }

        val settings = Json.obj(school.settings)
        if (settings.get("aiAssistantEnabled")?.asBoolean(true) == false) {
            throw ApiException.featureNotAvailable("AI yordamchi maktab sozlamalarida o'chirilgan")
        }

        if (feature == PlanFeatures.VECTOR_SEARCH && !flagService.isEnabled(FlagKeys.VECTOR_SEARCH, schoolId)) {
            throw ApiException.featureNotAvailable("Semantik qidiruv bu maktab uchun hali yoqilmagan")
        }
    }

    /** Tarifdagi o'quvchilar chegarasi (`max_students`); chegara yo'q bo'lsa — o'tkaziladi. */
    fun assertStudentCapacity(schoolId: UUID, adding: Int = 1) {
        val school = schools.findById(schoolId) ?: throw ApiException.notFound("Maktab", schoolId)
        val max = plans.findById(school.subscriptionPlan.name)?.maxStudents ?: return
        val current = (
            em.createNativeQuery("select count(*) from students where school_id = :s and status = 'ACTIVE'")
                .setParameter("s", schoolId)
                .singleResult as Number
            ).toLong()
        if (current + adding > max) {
            throw ApiException.featureNotAvailable(
                "«${school.subscriptionPlan.name}» tarifida ko'pi bilan $max ta o'quvchi. " +
                    "Hozir: $current. Tarifni oshirish uchun platforma bilan bog'laning.",
            )
        }
    }
}
