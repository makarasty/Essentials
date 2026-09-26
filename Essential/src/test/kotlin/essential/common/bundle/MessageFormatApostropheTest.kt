package essential.common.bundle

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * [Bundle.text] runs every key through MessageFormat whether or not it takes arguments (see
 * [Bundle.format]), so a single unescaped `'` anywhere in a bundle value opens a quoted span that
 * swallows everything up to the next `'` - including any `{0}`-style placeholder in between.
 * `command.tempBan.already.banned` did exactly this: "the ban's expiry has been changed to {1}"
 * printed literally from the apostrophe onward, `{1}` included.
 *
 * This formats every placeholder-taking key in every shipped bundle with placeholder-free dummy
 * arguments and checks the placeholders actually got replaced, so a fresh unescaped quote fails a
 * test instead of reaching a player.
 */
class MessageFormatApostropheTest {
    private val placeholder = Regex("\\{(\\d+)(?:,(\\w+))?[^}]*}")

    private val baseNames = listOf("bundles/common/bundle", "bundles/achievements/bundle")
    private val locales = listOf(
        Locale.ENGLISH, Locale.JAPANESE, Locale.KOREAN,
        Locale.forLanguageTag("uk"), Locale.forLanguageTag("zh"),
    )

    private fun dummyArg(formatType: String?): Any = when (formatType) {
        "number", "choice" -> 42
        "date", "time" -> java.util.Date()
        else -> "DUMMY"
    }

    @Test
    fun everyPlaceholderSurvivesFormatting() {
        for (baseName in baseNames) {
            for (locale in locales) {
                val bundle = Bundle(baseName, locale)
                for (key in bundle.resource.keys) {
                    val pattern = bundle.resource.getString(key)
                    val matches = placeholder.findAll(pattern).toList()
                    if (matches.isEmpty()) continue

                    val typeByIndex = matches.associate { it.groupValues[1].toInt() to it.groupValues[2].ifEmpty { null } }
                    val args = (0..typeByIndex.keys.max()).map { dummyArg(typeByIndex[it]) }.toTypedArray()

                    val formatted = bundle.get(key, *args)
                    assertFalse(
                        placeholder.containsMatchIn(formatted),
                        "$baseName ($locale) key '$key' still shows a raw placeholder after formatting: $formatted"
                    )
                }
            }
        }
    }
}
