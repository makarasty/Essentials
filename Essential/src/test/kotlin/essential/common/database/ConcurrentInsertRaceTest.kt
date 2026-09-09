package essential.common.database

import PluginTest.Companion.createPlayer
import PluginTest.Companion.loadGame
import essential.common.database.data.createPlayerData
import essential.common.database.data.getPlayerData
import essential.common.database.data.hasAchievement
import essential.common.database.data.setAchievement
import essential.common.database.table.AchievementTable
import essential.common.database.table.PlayerTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Account creation and achievement award are both check-then-insert across two statements, with a
 * unique index as the only thing that actually serialises them. Six servers share one database, so two
 * of them processing the same join - a reconnect, a proxy sending it twice - both read the row as
 * absent and both insert. The loser used to get a constraint violation thrown out at it: for a join,
 * that meant the player did not get in over a row that was already there.
 *
 * Two connections from the pool stand in for two servers. The window is genuinely narrow, so each case
 * is attempted repeatedly; what is asserted is only the invariant, which has to hold on every attempt
 * whether that attempt raced or not.
 */
class ConcurrentInsertRaceTest {

    private companion object {
        const val ATTEMPTS = 25
    }

    @BeforeTest
    fun setup() = loadGame(true)

    @Test
    fun twoServersCreatingOnePlayerBothGetTheRow() = runBlocking {
        repeat(ATTEMPTS) {
            val player = createPlayer()
            try {
                val both = listOf(
                    async(Dispatchers.IO) { createPlayerData(player) },
                    async(Dispatchers.IO) { createPlayerData(player) },
                ).awaitAll()

                val stored = assertNotNull(
                    getPlayerData(player.uuid()),
                    "no row for ${player.uuid()} after two servers created it"
                )
                for (data in both) {
                    assertEquals(
                        stored.id, data.id,
                        "a server that lost the insert race was handed a different row than the one in the table"
                    )
                }
            } finally {
                player.remove()
                suspendTransaction { PlayerTable.deleteWhere { uuid eq player.uuid() } }
            }
        }
    }

    @Test
    fun twoServersAwardingOneAchievementBothSucceed() = runBlocking {
        val player = createPlayer()
        val data = createPlayerData(player)
        try {
            repeat(ATTEMPTS) { attempt ->
                val name = "race-achievement-$attempt"
                listOf(
                    async(Dispatchers.IO) { setAchievement(data, name) },
                    async(Dispatchers.IO) { setAchievement(data, name) },
                ).awaitAll()

                // Re-read through the table rather than trusting data.achievementStatus: an in-memory
                // list saying the achievement is held proves nothing about the row.
                assertTrue(
                    hasAchievement(data, name),
                    "$name was not recorded when two servers awarded it at once"
                )
                assertEquals(
                    1L,
                    suspendTransaction {
                        AchievementTable.selectAll()
                            .where { (AchievementTable.playerId eq data.id) and (AchievementTable.achievementName eq name) }
                            .count()
                    },
                    "$name was recorded twice"
                )
            }
        } finally {
            suspendTransaction { AchievementTable.deleteWhere { playerId eq data.id } }
            suspendTransaction { PlayerTable.deleteWhere { uuid eq player.uuid() } }
            player.remove()
        }
    }
}
