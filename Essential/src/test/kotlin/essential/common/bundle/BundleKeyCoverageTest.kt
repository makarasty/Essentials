package essential.common.bundle

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [Bundle.get] fails open: a key no bundle carries is returned verbatim and its arguments are dropped, so
 * a missing key reaches the player as raw identifier text rather than failing anywhere visible.
 */
class BundleKeyCoverageTest {
    private val keys = listOf(
        "command.changeName.success",
        "command.fuck.no.command",
        "command.meme.not.found",
        "permission.denied"
    )

    private fun english() = Bundle("bundles/common/bundle", Locale.ENGLISH)

    @Test
    fun everyKeyTheCodeSendsResolves() {
        val bundle = english()
        keys.forEach { key ->
            assertTrue(bundle.resource.containsKey(key), "$key is in no bundle, so players are shown the key")
        }
    }

    @Test
    fun changeNameSuccessCarriesTheNewName() {
        val message = english()["command.changeName.success", "Newname"]
        assertNotEquals("command.changeName.success", message)
        assertTrue(message.contains("Newname"), "the new name should reach the player, but the message was: $message")
    }

    @Test
    fun translatedBundlesReachTheseKeysThroughTheirParent() {
        // Deliberately left untranslated. An English line copied into bundle_ko.properties is worse than
        // the absence, because the parent chain already reaches the English one and a copy hides the gap.
        listOf("ko", "ja", "uk", "zh").forEach { tag ->
            val bundle = Bundle(tag)
            keys.forEach { key ->
                assertNotEquals(key, bundle[key], "$key should resolve through the base bundle for $tag")
            }
        }
    }
}
