import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.WorldHistoryBuffer
import essential.common.database.data.PlayerData
import essential.common.database.data.getAllWorldHistory
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.gen.Player
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * World history rows carry tile coordinates and no map identity, so they only mean anything inside the
 * world they were recorded in. Replacing the world without a game over used to leave them in place, and a
 * rollback then rebuilt blocks from the previous map onto whatever now stood at the same coordinates.
 */
class WorldHistoryScopeTest {
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

    private fun recordOneRow() {
        WorldHistoryBuffer.enqueue(
            time = System.currentTimeMillis(),
            player = "someone",
            action = "place",
            x = 7,
            y = 9,
            tile = "copper-wall",
            rotate = 0,
            team = "sharded",
            value = null
        )
        runBlocking { WorldHistoryBuffer.flush() }
        assertTrue(
            waitUntil(10000) { runBlocking { getAllWorldHistory() }.isNotEmpty() },
            "the row should be in the table before the command runs"
        )
    }

    @Test
    fun changeMap_dropsTheHistoryOfTheMapItReplaced() {
        val (player, _) = admin()
        recordOneRow()

        clientCommand.handleMessage("/changemap fork survival", player)

        assertEquals("Fork", Vars.state.map.name(), "the map should actually have changed")
        assertTrue(
            waitUntil(10000) { runBlocking { getAllWorldHistory() }.isEmpty() },
            "history recorded on the previous map must not survive into the new one"
        )

        // Put the suite back on the map ClientCommandTest already leaves it on. A test class that ends on
        // a different map changes the world every later class stands on, and createPlayer spawns its unit
        // at coordinates up to 300x500 - on a smaller map the spawn falls outside and the player is left
        // with no unit, which makes leavePlayer throw in whichever class runs next.
        clientCommand.handleMessage("/changemap glacier", player)
        assertEquals("Glacier", Vars.state.map.name(), "the suite should be left on the map it started on")
    }

    @Test
    fun changeMap_keepsHistoryWhenNoMapWasLoaded() {
        val (player, data) = admin()
        recordOneRow()

        clientCommand.handleMessage("/changemap definitely-not-a-map", player)

        assertEquals(err("command.changeMap.map.not.found", "definitely-not-a-map"), data.lastReceivedMessage)
        assertTrue(
            runBlocking { getAllWorldHistory() }.isNotEmpty(),
            "history belongs to the loaded world, so a map change that loaded nothing must not drop it"
        )
    }
}
