package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import PluginTest.Companion.waitUntil
import essential.common.database.data.getPlayerData
import kotlinx.coroutines.runBlocking
import essential.core.service.achievements.gameover as achievementGameover
import essential.core.service.achievements.playerJoin as achievementPlayerJoin
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AchievementLeaveTest {
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

    @AfterTest
    fun drain() {
        pumpApp()
    }

    @Test
    fun leavingAPvpGameOnTheLosingSideIsCreditedOnce() {
        val (player, data) = newPlayer()
        val wasPvp = Vars.state.rules.pvp
        try {
            Vars.state.rules.pvp = true
            data.status.remove("record.pvp.leave.lose")

            // The real leave path, so core's own handler runs first and takes the player out of
            // `players` before the achievements one looks for them.
            leavePlayer(player)

            achievementGameover(EventType.GameOverEvent(Team.crux))
            assertEquals(
                "1",
                data.status["record.pvp.leave.lose"],
                "A player who left a pvp game their team then lost must be credited."
            )

            achievementGameover(EventType.GameOverEvent(Team.crux))
            assertEquals(
                "1",
                data.status["record.pvp.leave.lose"],
                "A player who never came back must not be credited again by later games."
            )

            // The counter has to reach the row: the player left before it moved, so nothing else
            // will ever write it, and a counter that only lives in memory can never reach 10.
            assertTrue(
                waitUntil(10000) {
                    runBlocking { getPlayerData(player.uuid()) }
                        ?.status?.get("record.pvp.leave.lose") == "1"
                },
                "The increment must be written to the players row."
            )
        } finally {
            Vars.state.rules.pvp = wasPvp
        }
    }

    @Test
    fun leavingOnTheWinningSideIsNotCredited() {
        val (player, data) = newPlayer()
        val wasPvp = Vars.state.rules.pvp
        try {
            Vars.state.rules.pvp = true
            data.status.remove("record.pvp.leave.lose")

            leavePlayer(player)

            achievementGameover(EventType.GameOverEvent(player.team()))
            assertNull(
                data.status["record.pvp.leave.lose"],
                "Leaving a game the team went on to win is not leaving and losing."
            )
        } finally {
            Vars.state.rules.pvp = wasPvp
        }
    }

    @Test
    fun comingBackBeforeTheGameEndsCancelsTheCredit() {
        val (player, data) = newPlayer()
        val wasPvp = Vars.state.rules.pvp
        try {
            Vars.state.rules.pvp = true
            data.status.remove("record.pvp.leave.lose")

            leavePlayer(player)
            achievementPlayerJoin(EventType.PlayerJoin(player))

            achievementGameover(EventType.GameOverEvent(Team.crux))
            assertNull(
                data.status["record.pvp.leave.lose"],
                "Someone who came back and played the game out did not leave and lose it."
            )
        } finally {
            Vars.state.rules.pvp = wasPvp
        }
    }
}
