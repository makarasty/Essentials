package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import PluginTest.Companion.waitUntil
import arc.util.Timer
import essential.core.service.achievements.achievementSweep
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

            // Arc's Timer does not call the sweep from its own thread - see the test below - so this
            // stands in for nothing that happens in production. It still pins what the post is for:
            // whoever calls achievementSweep, the writes land on the app queue rather than on the
            // calling thread.
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

    @Test
    fun arcTimerHandsTaskBodiesToTheApplicationQueue() {
        // Three separate audits have read Timer.java:303 - which does start a daemon thread - and
        // concluded that the thread runs the task bodies too. It does not: Timer.update posts them
        // (Timer.java:211). This pins that, so the next reader meets a measurement instead of a claim.
        val ranOn = AtomicReference<String>()
        val task = Timer.schedule({ ranOn.set(Thread.currentThread().name) }, 0f)
        try {
            assertTrue(
                waitUntil(5000) { ranOn.get() != null },
                "The scheduled task never ran at all."
            )
            assertNotEquals(
                "Timer",
                ranOn.get(),
                "Arc's Timer posts a task body to Core.app rather than running it on its own thread, " +
                        "so the body lands on whoever drains that queue - in production, the game thread."
            )
        } finally {
            task.cancel()
        }
    }
}
