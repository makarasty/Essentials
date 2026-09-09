package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import essential.core.service.achievements.achievementSweep
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AchievementSweepTest {
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
    fun theSecondSweepTouchesPlayerStateOnlyFromTheGameThread() {
        val (player, data) = newPlayer()
        try {
            pumpApp()
            data.status["record.warp.disconnect.duration"] = "sentinel"

            // Stand in for Arc's Timer daemon thread, which is what really calls the sweep.
            val timerThread = Thread { achievementSweep() }
            timerThread.start()
            timerThread.join(5000)

            assertEquals(
                "sentinel",
                data.status["record.warp.disconnect.duration"],
                "The sweep must not write player state from the thread that called it."
            )

            pumpApp()
            assertNotEquals(
                "sentinel",
                data.status["record.warp.disconnect.duration"],
                "The sweep must still do its work once the game thread drains its queue."
            )
        } finally {
            leavePlayer(player)
        }
    }
}
