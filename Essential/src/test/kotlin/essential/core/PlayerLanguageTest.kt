package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.loadGame
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.createTemporaryPlayerData
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three ways a player's language can be decided, in the order they win: what they picked with
 * `/lang`, what their client asks for, and what the server itself is configured in.
 */
class PlayerLanguageTest {
    private lateinit var originalConf: CoreConfig

    @BeforeTest
    fun setup() {
        loadGame()
        originalConf = Main.conf
    }

    @AfterTest
    fun cleanup() {
        Main.conf = originalConf
    }

    /** A player whose client speaks [clientLocale], with no database behind them. */
    private fun player(clientLocale: String): PlayerData {
        val player = createPlayer()
        player.locale(clientLocale)
        return createTemporaryPlayerData(player).apply { temporary = true }
    }

    private fun serverLanguage(tag: String) {
        Main.conf = Main.conf.copy(plugin = Main.conf.plugin.copy(lang = tag))
    }

    @Test
    fun the_client_language_is_used_when_a_translation_ships_for_it() {
        serverLanguage("ko")
        assertEquals("uk", player("uk").localeTag())
        assertEquals("성공.", player("ko").bundle["success"])
    }

    @Test
    fun an_untranslated_client_language_falls_back_to_the_server_language() {
        serverLanguage("ko")
        // de ships no bundle. English is what it used to get, and on a Korean server the server's own
        // language is the better answer - it is the one the rest of the server is written in.
        assertEquals("ko", player("de").localeTag())
        assertEquals("성공.", player("de").bundle["success"])
    }

    @Test
    fun english_is_the_base_bundle_and_counts_as_translated() {
        serverLanguage("ko")
        // There is no bundle_en.properties; en resolves to the base bundle and must not be mistaken
        // for a missing translation, or every English client on a Korean server would read Korean.
        assertTrue(Bundle.translated(Locale.ENGLISH))
        assertEquals("en", player("en").localeTag())
        assertEquals("Success.", player("en-US").bundle["success"])
    }

    @Test
    fun a_chosen_language_outlives_the_client_and_the_server_setting() {
        serverLanguage("ko")
        val data = player("uk")
        Commands().lang(data, arrayOf("ja"))

        assertEquals("ja", data.languageChoice)
        assertEquals("ja", data.localeTag())
        // The client still says uk and the server still says ko; neither gets a say any more.
        assertEquals("成功。", data.bundle["success"])
    }

    @Test
    fun auto_gives_the_client_its_say_back() {
        serverLanguage("ko")
        val data = player("uk")
        Commands().lang(data, arrayOf("ja"))
        Commands().lang(data, arrayOf("auto"))

        assertNull(data.languageChoice)
        assertEquals("uk", data.localeTag())
    }

    @Test
    fun a_language_the_server_does_not_have_is_refused_rather_than_stored() {
        serverLanguage("ko")
        val data = player("uk")
        Commands().lang(data, arrayOf("ja"))
        Commands().lang(data, arrayOf("de"))
        Commands().lang(data, arrayOf("not a language"))

        assertEquals("ja", data.languageChoice, "a refused language must not replace the one that took")
    }

    @Test
    fun the_offered_list_is_read_off_the_shipped_bundles() {
        // Every translation the repository carries, and nothing invented: the list is what /lang
        // prints, so a language on it that resolves to English would send a player in circles.
        assertTrue(Bundle.translations.containsAll(listOf("en", "ja", "ko", "uk", "zh")))
        assertTrue(Bundle.translations.none { it == "de" || it == "fr" })
    }
}
