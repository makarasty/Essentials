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
 * to be bounded. It is the same bound /vote skip has always applied.
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
        val limit = conf.command.skip.limit
        val before = Vars.state.wave

        clientCommand.handleMessage("/skip ${limit + 1}", player)

        assertEquals(before, Vars.state.wave, "a count over the limit must spawn nothing at all")
        assertEquals(err("command.vote.skip.tooMany"), data.lastReceivedMessage, "the player should be told why")
    }

    @Test
    fun skip_stillSkipsUpToTheLimit() {
        val (player, _) = admin()
        val limit = conf.command.skip.limit
        val before = Vars.state.wave

        assertTrue(limit > 0, "the limit has to leave the command usable")
        clientCommand.handleMessage("/skip $limit", player)

        assertEquals(before + limit, Vars.state.wave, "a count within the limit must still skip that many waves")
    }
}
