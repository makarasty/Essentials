package essential.core

import PluginTest.Companion.clientCommand
import PluginTest.Companion.createPlayer
import PluginTest.Companion.leavePlayer
import essential.core.ui.UiHarness
import mindustry.gen.TextInputCallPacket2
import mindustry.ui.Menus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An online target whose PlayerData is not loaded yet has no session id: /pm and /report must re-run with the typed name, not `#null`. */
class PmReportTargetTest {
    @BeforeTest
    fun setup() = UiHarness.load()

    @AfterTest
    fun restore() = UiHarness.netServer(false)

    private fun withLoadingTarget(command: String, text: String): String {
        val me = UiHarness.joined("owner")
        val loading = createPlayer()
        try {
            clientCommand.handleMessage("/$command ${loading.plainName()}", me.player)
            me.data.lastReceivedMessage = ""
            Menus.textInputResult(me.player, me.last<TextInputCallPacket2>().textInputId, text)
            return me.data.lastReceivedMessage
        } finally {
            leavePlayer(loading)
            leavePlayer(me.player)
        }
    }

    @Test
    fun pmReachesATargetWithoutPlayerData() {
        val told = withLoadingTarget("pm", "zzhello")
        assertTrue(told.contains("zzhello"), told)
    }

    @Test
    fun reportAcceptsATargetWithoutPlayerData() {
        val told = withLoadingTarget("report", "griefing")
        assertTrue(told.isNotEmpty(), "told")
        assertFalse(told.contains("null"), told)
    }
}
