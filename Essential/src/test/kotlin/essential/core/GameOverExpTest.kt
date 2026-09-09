package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import arc.Events
import mindustry.game.EventType.WorldLoadEvent
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GameOverExpTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
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
