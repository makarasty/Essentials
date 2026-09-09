package essential.core

import PluginTest.Companion.loadGame
import essential.common.database.data.plugin.WarpCount
import essential.common.database.data.plugin.WarpTotal
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Team
import mindustry.net.Host
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * task-119, refuted per answers/6-3.md: the warp count and warp total displays used to clear a 3x5 or
 * 6x5 rectangle of tiles at the warp anchor - one server-local ([mindustry.world.Tile.setBlock]), one
 * broadcast to every client ([mindustry.gen.Call.setTile]) - to make space for a digit count that
 * nothing in this repository draws. A changing remote player total silently deleted whatever a player
 * had built there. Both erase loops are gone; this proves neither rectangle is touched anymore when
 * the counts that used to trigger them differ.
 *
 * Drives `PingThread.drawCycle` directly through reflection: it is private, and the real path (a
 * background thread pinging configured hosts over the network every 3 seconds) is not something a
 * unit test should drive.
 */
class TriggerWarpEraseTest {
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

    @Test
    fun aDifferingCountNoLongerErasesTheWarpAnchorRectangle() {
        val mapName = Vars.state.map.name()
        val countAnchor = Vars.world.tile(20, 20) ?: fail("the test map has no tile at 20,20")
        val totalAnchor = Vars.world.tile(40, 20) ?: fail("the test map has no tile at 40,20")

        // Inside the warp count rectangle: tile.x+4+px (px 0..2), tile.y+py (py 0..4).
        val countTarget = Vars.world.tile(countAnchor.x + 5, countAnchor.y + 2)
            ?: fail("the test map has no tile inside the warp count rectangle")
        // Inside the warp total rectangle for cycle.total in 0..9: tile.x+px (px 0..2), tile.y+py (py 0..4).
        val totalTarget = Vars.world.tile(totalAnchor.x + 1, totalAnchor.y + 2)
            ?: fail("the test map has no tile inside the warp total rectangle")

        countTarget.setBlock(Blocks.copperWall, Team.sharded)
        totalTarget.setBlock(Blocks.copperWall, Team.sharded)

        try {
            // One remote host, one player - the mismatch (WarpCount.players defaults to 0,
            // WarpTotal.totalPlayers set to 5 below) is what used to gate both erase loops. Port set
            // explicitly rather than relied on: hostFor matches on it, and the constructor overload
            // used here happens to default it to 6567 (verified via javap on Host's bytecode), but
            // that is an implementation detail of arc's Host class, not this test's to depend on.
            val host = Host(0, "test-server", "127.0.0.1", mapName, 0, 1, 0, null, null, 0, null, null)
                .also { it.port = 6567 }
            val warpCount = WarpCount(mapName, countAnchor.pos(), "127.0.0.1", 6567)
            val warpTotal = WarpTotal(mapName, totalAnchor.pos(), totalPlayers = 5, numberSize = 1)

            val cycleClass = Class.forName("essential.core.Trigger\$PingThread\$Cycle")
            val cycleCtor = cycleClass.getDeclaredConstructor(
                List::class.java, List::class.java, List::class.java, List::class.java,
                Set::class.java, Map::class.java, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            val cycle = cycleCtor.newInstance(
                listOf(warpCount), listOf(warpTotal), emptyList<Any>(), emptyList<Any>(),
                setOf(host), emptyMap<String, String?>(), 1, 3f
            )

            val pingThread = Trigger.PingThread()
            val drawCycle = Trigger.PingThread::class.java.getDeclaredMethod("drawCycle", cycleClass)
            drawCycle.isAccessible = true
            drawCycle.invoke(pingThread, cycle)

            assertEquals(
                Blocks.copperWall, countTarget.block(),
                "the warp count display must not erase a player's build anymore"
            )
            assertEquals(
                Blocks.copperWall, totalTarget.block(),
                "the warp total display must not erase a player's build anymore"
            )
        } finally {
            countTarget.setBlock(Blocks.air)
            totalTarget.setBlock(Blocks.air)
        }
    }
}
