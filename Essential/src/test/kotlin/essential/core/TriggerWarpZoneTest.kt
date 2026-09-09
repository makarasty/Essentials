package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import arc.Events
import essential.common.database.data.plugin.WarpZone
import essential.common.players
import essential.common.pluginData
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Player
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A player with no unit - dead on a team that has no core left to respawn from - made the
 * Trigger.update listener dereference null once per frame while a warp zone was configured for the
 * current map. Arc's Events.fire has no handler of its own, so the throw left the server's update
 * loop entirely.
 */
class TriggerWarpZoneTest {
    companion object {
        private var done = false
        private lateinit var testPlayer: Player
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            testPlayer = newPlayer().first
            done = true
        }
    }

    @Test
    fun triggerUpdateSurvivesAPlayerWithNoUnit() {
        val originalZones = pluginData.data.warpZone.toList()
        val originalUnit = testPlayer.unit()
        val originalTeam = testPlayer.team()

        try {
            pluginData.data.warpZone.clear()
            pluginData.data.warpZone.add(
                WarpZone(
                    mapName = Vars.state.map.name(),
                    start = (Vars.world.tile(1, 1) ?: fail("the test map has no tile at 1,1")).pos(),
                    finish = (Vars.world.tile(5, 5) ?: fail("the test map has no tile at 5,5")).pos(),
                    click = false,
                    ip = "127.0.0.1",
                    port = 6567
                )
            )

            // The listener returns early while any tracked player is stale, so flush that first,
            // otherwise this test would pass without ever reaching the warp zone loop.
            Events.fire(EventType.Trigger.update)
            assertTrue(
                players.isNotEmpty() && players.none { it.player.con() == null || it.player.con().hasDisconnected },
                "the listener skips its whole body while a stale player is tracked, so the reproduction needs a live one"
            )
            assertTrue(
                players.any { it.uuid == testPlayer.uuid() },
                "the test player has to be tracked for the listener to read its unit"
            )

            // Team.derelict has no cores, so PlayerComp.update never respawns this player and unit()
            // stays null - the exact state /vote or a pvp defeat leaves a player in.
            testPlayer.team(Team.derelict)
            testPlayer.clearUnit()
            assertNull(testPlayer.unit(), "the reproduction needs a player with no unit")

            Events.fire(EventType.Trigger.update)
        } finally {
            testPlayer.team(originalTeam)
            if (originalUnit != null) testPlayer.unit(originalUnit)
            pluginData.data.warpZone.clear()
            pluginData.data.warpZone.addAll(originalZones)
        }
    }
}
