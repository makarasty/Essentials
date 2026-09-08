import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import essential.common.database.data.PlayerData
import essential.core.Main.Companion.conf
import mindustry.Vars
import mindustry.gen.Player
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * /skip spawns its waves in one synchronous loop on the main thread, so the count a caller may ask for has
 * to be bounded. It has its own bound rather than the vote path's: an operator who capped vote skips did
 * not ask for their own admin command to be capped with them.
 */
class SkipLimitTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    private fun admin(): Pair<Player, PlayerData> = newPlayer().also { setPermission(it.first, "owner", true) }

    @Test
    fun skip_refusesMoreWavesThanTheLimit() {
        val (player, data) = admin()
        val limit = conf.command.skip.adminLimit
        val before = Vars.state.wave

        clientCommand.handleMessage("/skip ${limit + 1}", player)

        assertEquals(before, Vars.state.wave, "a count over the limit must spawn nothing at all")
        assertEquals(
            err("command.skip.number.high", limit),
            data.lastReceivedMessage,
            "the refusal should name the limit and the setting that raises it"
        )
    }

    @Test
    fun skip_stillSkipsWithinTheLimit() {
        val (player, _) = admin()
        // Above the vote limit and below the admin one: the band this setting exists to create.
        val asked = conf.command.skip.limit + 1
        val before = Vars.state.wave

        assertTrue(conf.command.skip.adminLimit >= asked, "the command must not be bounded by the vote limit")
        clientCommand.handleMessage("/skip $asked", player)

        assertEquals(before + asked, Vars.state.wave, "a count within the limit must still skip that many waves")
    }
}
