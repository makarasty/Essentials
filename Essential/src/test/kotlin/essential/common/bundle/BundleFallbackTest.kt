package essential.common.bundle

import java.util.Locale
import java.util.ResourceBundle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ResourceBundle's documented search order reaches the JVM default locale's bundle before the base one.
 * This ships ja, ko, uk and zh, so on a ko-locale host every client whose language ships no bundle was
 * answered in Korean rather than English, and the same build behaved differently per host.
 */
class BundleFallbackTest {
    // Locale.setDefault is JVM-global and covers both DISPLAY and FORMAT. Safe under Gradle's default
    // sequential class execution and restored in the finally, but this class must not be run in a JVM
    // shared with parallel tests.
    private fun <T> withDefaultLocale(locale: Locale, body: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        // The resolution is cached per base name and locale, so an earlier test's lookup would otherwise
        // decide what this one sees.
        ResourceBundle.clearCache(Bundle::class.java.classLoader)
        return try {
            body()
        } finally {
            Locale.setDefault(previous)
            ResourceBundle.clearCache(Bundle::class.java.classLoader)
        }
    }

    @Test
    fun unshippedLanguageIsAnsweredInEnglishNotTheHostLanguage() {
        withDefaultLocale(Locale.KOREAN) {
            assertEquals("Success.", Bundle("de")["success"], "a de client on a ko host must read English")
            assertEquals("Success.", Bundle("fr")["success"])
            assertEquals("Success.", Bundle("bundles/common/bundle", Locale.forLanguageTag("es"))["success"])
        }
        withDefaultLocale(Locale.JAPANESE) {
            assertEquals("Success.", Bundle("de")["success"], "a de client on a ja host must read English")
        }
    }

    @Test
    fun shippedLanguageStillResolvesToItsOwnBundle() {
        withDefaultLocale(Locale.ENGLISH) {
            assertEquals("성공.", Bundle("ko")["success"])
        }
        withDefaultLocale(Locale.KOREAN) {
            assertEquals("성공.", Bundle("ko")["success"])
        }
    }
}
