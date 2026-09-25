package essential.core.service.web

import essential.common.database.data.getAverageContribution
import essential.common.database.data.getContributionCount
import essential.common.database.data.getPlayerContributions
import essential.common.database.data.createTemporaryPlayerData
import essential.common.database.data.insertContribution
import essential.core.service.contribution.gameOver
import mindustry.game.EventType.GameOverEvent
import mindustry.game.Team
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContributionTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            PluginTest.loadGame(true)
            done = true
        }
    }

    @Test
    fun contribution_insertAndAverage() {
        val target = PluginTest.newPlayer()
        runBlocking {
            insertContribution(target.second, "pvp", "map-a", 100.0)
            insertContribution(target.second, "pvp", "map-b", 200.0)
            insertContribution(target.second, "survival", "map-c", 300.0)

            assertEquals(3, getContributionCount(target.second))
            assertEquals(200.0, getAverageContribution(target.second), 0.001)

            val rows = getPlayerContributions(target.second)
            assertEquals(3, rows.size)
        }
    }

    /**
     * Game over writes every player's score in one batch. It used to be a coroutine per player with any
     * failure swallowed, so a batch that silently wrote nothing would look the same as the old code
     * losing rows - hence waiting for the rows themselves, not for the launch.
     */
    @Test
    fun contribution_gameOverWritesEveryScore() {
        val a = PluginTest.newPlayer()
        val b = PluginTest.newPlayer()
        a.second.currentContribution = 10.0
        b.second.currentContribution = 20.0

        gameOver(GameOverEvent(Team.sharded))

        runBlocking {
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline &&
                (getContributionCount(a.second) < 1 || getContributionCount(b.second) < 1)
            ) kotlinx.coroutines.delay(50)
            assertEquals(1, getContributionCount(a.second), "first player's score was not saved")
            assertEquals(1, getContributionCount(b.second), "second player's score was not saved")
            assertEquals(20.0, getAverageContribution(b.second), 0.001)
            // resetGameState ran after the scores were read, not before.
            assertEquals(0.0, b.second.currentContribution, 0.001)
        }
    }

    @Test
    fun contribution_batchSkipsTemporaryPlayers() {
        val real = PluginTest.newPlayer()
        val temporary = createTemporaryPlayerData(PluginTest.createPlayer())
        runBlocking {
            essential.common.database.data.insertContributions(
                listOf(real.second to 5.0, temporary to 7.0), "pvp", "map-d"
            )
            assertEquals(1, getContributionCount(real.second))
            assertEquals(0, getContributionCount(temporary))
        }
    }

    @Test
    fun contribution_emptyAverageIsZero() {
        val target = PluginTest.newPlayer()
        runBlocking {
            assertEquals(0, getContributionCount(target.second))
            assertEquals(0.0, getAverageContribution(target.second), 0.001)
        }
    }
}