package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import essential.common.database.data.getPlayerData
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PlayerStatusPersistenceTest {
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
    fun achievementCountersSurviveALeaveAndRejoin() {
        val (player, data) = newPlayer()
        try {
            // 90 minutes on Serpulo and seven map clears, the kind of progress that used to be lost.
            data.status["record.time.serpulo"] = "5400"
            data.status["record.map.clear.count"] = "7"

            runBlocking {
                assertTrue(data.update(), "The row must be written.")

                val reloaded = getPlayerData(player.uuid())
                    ?: fail("Player ${player.uuid()} disappeared from the database.")

                assertEquals(
                    "5400",
                    reloaded.status["record.time.serpulo"],
                    "Achievement progress must come back from the players row."
                )
                assertEquals("7", reloaded.status["record.map.clear.count"])
            }
        } finally {
            leavePlayer(player)
        }
    }

    @Test
    fun sessionOnlyFlagsAreNotCarriedIntoTheNextSession() {
        val (player, data) = newPlayer()
        try {
            // pendingLogin is the confirmation token for deleting the player's own row, and the hub
            // keys are a half-finished block selection. Neither may outlive the session that set it.
            data.status["pendingLogin"] = "someaccount"
            data.status["hub_block_selecting"] = "true"

            runBlocking {
                assertTrue(data.update(), "The row must be written.")

                val reloaded = getPlayerData(player.uuid())
                    ?: fail("Player ${player.uuid()} disappeared from the database.")

                assertNull(
                    reloaded.status["pendingLogin"],
                    "A confirmation that outlives its conversation is a confirmation nobody gave."
                )
                assertNull(reloaded.status["hub_block_selecting"])
            }
        } finally {
            leavePlayer(player)
        }
    }
}
