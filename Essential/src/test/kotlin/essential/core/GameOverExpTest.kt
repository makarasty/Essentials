package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import essential.common.offlinePlayers
import arc.Events
import mindustry.game.EventType.WorldLoadEvent
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameOverExpTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
    }

    /**
     * task-091: a player who leaves and rejoins used to leave a stale duplicate parked in
     * offlinePlayers forever, because nothing ever removed one on rejoin - the only add was at
     * playerLeave and a repo-wide grep found no remove/removeIf at all before this fix.
     */
    @Test
    fun rejoiningRemovesTheStaleOfflineEntry() {
        val (player, data) = newPlayer()
        try {
            leavePlayer(player)
            assertTrue(
                offlinePlayers.any { it.uuid == data.uuid },
                "leaving must park the player in offlinePlayers"
            )

            // Same uuid, a fresh connection - a real rejoin, not the same in-memory object.
            val rejoinPlayer = createPlayer()
            rejoinPlayer.con.uuid = data.uuid
            val rejoined = joinPlayer(rejoinPlayer)
            assertEquals(
                0,
                offlinePlayers.count { it.uuid == rejoined.uuid },
                "a rejoin must clear the leave-time snapshot - the player is online again, not offline"
            )

            // Leave a second time: without the fix this is the second unremoved add, so gameOver
            // would iterate this uuid twice and earn its match EXP twice.
            leavePlayer(rejoined.player)
            assertEquals(
                1,
                offlinePlayers.count { it.uuid == rejoined.uuid },
                "one leave-rejoin-leave cycle must leave exactly one offlinePlayers entry, not a duplicate"
            )
        } finally {
            offlinePlayers.removeIf { it.uuid == data.uuid }
        }
    }

    /**
     * task-093: currentUnitDestroyedCount/currentBuildDestroyedCount/currentBuildAttackCount fed
     * straight into earnEXP's score and were never zeroed anywhere in the repository, so a player
     * connected across several maps carried one map's counters into the next map's EXP calculation.
     */
    @Test
    fun worldLoadResetsTheCombatCountersEarnEXPReads() {
        val (player, data) = newPlayer()
        try {
            data.currentUnitDestroyedCount = 7
            data.currentBuildDestroyedCount = 3
            data.currentBuildAttackCount = 5

            Events.fire(WorldLoadEvent())

            assertEquals(0, data.currentUnitDestroyedCount, "currentUnitDestroyedCount must not survive a map load")
            assertEquals(0, data.currentBuildDestroyedCount, "currentBuildDestroyedCount must not survive a map load")
            assertEquals(0, data.currentBuildAttackCount, "currentBuildAttackCount must not survive a map load")
        } finally {
            leavePlayer(player)
        }
    }
}
