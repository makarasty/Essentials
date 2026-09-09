package essential.core.service.web.achievement

import java.util.Locale
import java.util.ResourceBundle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-044 / answers/3-2.md: `resolveAchievementBundle` (extracted from `AchievementController.getMyInfo`)
 * must go through `Bundle.resolve`, not `ResourceBundle.getBundle`. The two-arg form falls through the
 * JVM default locale's candidates before reaching the base bundle, so a client whose language ships no
 * `bundles/achievements/bundle_*.properties` file was answered in the host's language instead of English.
 */
class AchievementControllerBundleTest {
    // Locale.setDefault is JVM-global. Safe under Gradle's default sequential class execution, restored
    // in the finally - this class must not run in a JVM shared with parallel tests.
    private fun <T> withDefaultLocale(locale: Locale, body: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        // Cached per base name and locale, so a stale entry from an earlier test would otherwise decide
        // what this one sees.
        ResourceBundle.clearCache(this::class.java.classLoader)
        return try {
            body()
        } finally {
            Locale.setDefault(previous)
            ResourceBundle.clearCache(this::class.java.classLoader)
        }
    }

    @Test
    fun unshippedLanguageIsAnsweredInEnglishNotTheHostLanguage() {
        withDefaultLocale(Locale.KOREAN) {
            val bundle = resolveAchievementBundle(Locale.forLanguageTag("de"))
            assertEquals(
                "Builder",
                bundle.getString("achievement.builder"),
                "a de client on a ko host must read the English achievements bundle"
            )
        }
    }

    @Test
    fun shippedLanguageStillResolvesToItsOwnBundle() {
        withDefaultLocale(Locale.ENGLISH) {
            val bundle = resolveAchievementBundle(Locale.forLanguageTag("ko"))
            assertEquals("건축가", bundle.getString("achievement.builder"))
        }
    }
}
