package essential.common.database

import PluginTest.Companion.createPlayer
import PluginTest.Companion.loadGame
import arc.util.Log
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
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
 *
 * **Both tests state the precondition the repair rests on rather than inheriting it.** The suite runs
 * in one JVM and only the first `loadGame(true)` of a run boots a database, so a class inherits
 * whatever database the class before it left behind - and a legacy-built schema carries none of the
 * unique indexes the Kotlin tables declare, which is what the six live servers are running. Against
 * such a database both repairs are no-ops and both inserts succeed, so each test hands the engine a
 * duplicate first and fails naming the database and its indexes if the engine takes it.
 *
 * [twoServersCreatingOnePlayerBothGetTheRow] also counts how many of its attempts genuinely raced, so
 * a run in which the two calls happened to be serialised - and which therefore never reached the
 * branch under test - is red rather than a vacuous green. That count comes from `createPlayerData`
 * logging its refusal. `setAchievement` says nothing on that path and its fire rate is not observable
 * from here; `ask/16-2.md` of run `backlog-2026-09-10` asks for the matching line.
 */
class ConcurrentInsertRaceTest {

    private companion object {
        const val ATTEMPTS = 25

        /** What `createPlayerData` says when the engine refused its insert. */
        const val REFUSAL = "Insert refused for"
    }

    private val refusals = AtomicInteger()
    private var previousLogger: Log.LogHandler? = null

    @BeforeTest
    fun setup() {
        loadGame(true)
        // Delegating rather than replacing: loadGame's handler turns a Log.err into a failure, and a
        // counter that quietly took that away would hide the failures this class exists to catch.
        val previous = Log.logger
        previousLogger = previous
        Log.logger = Log.LogHandler { level, text ->
            if (text.contains(REFUSAL)) refusals.incrementAndGet()
            previous.log(level, text)
        }
    }

    @AfterTest
    fun restoreLogger() {
        previousLogger?.let { Log.logger = it }
        previousLogger = null
    }

    @Test
    fun twoServersCreatingOnePlayerBothGetTheRow() = runBlocking {
        assertPlayerUuidIsUnique()

        var raced = 0
        repeat(ATTEMPTS) {
            val player = createPlayer()
            val before = refusals.get()
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
                if (refusals.get() > before) raced++
            } finally {
                player.remove()
                suspendTransaction { PlayerTable.deleteWhere { uuid eq player.uuid() } }
            }
        }

        println("[race] createPlayerData met a refusal on $raced of $ATTEMPTS attempts, against ${reachedDatabase()}")
        assertTrue(
            raced > 0,
            "on none of $ATTEMPTS attempts did a caller meet a refusal, so this test never reached the " +
                "branch that tolerates one and would have passed whether that branch worked or not"
        )
    }

    @Test
    fun twoServersAwardingOneAchievementBothSucceed() = runBlocking {
        val player = createPlayer()
        val data = createPlayerData(player)
        try {
            assertAchievementPairIsUnique(data.id)

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

    /**
     * The whole of `setAchievement`'s repair is the unique index refusing the second insert, so a run
     * against a database that has no such index proves nothing about it - it would race, insert twice
     * and be right about nothing. Asked of the engine by handing it a duplicate rather than by reading
     * an index name: what matters is enforcement, and only the engine knows.
     */
    private suspend fun assertAchievementPairIsUnique(playerId: UInt) {
        val probe = "unique-index-probe-${System.nanoTime()}"
        suspend fun insert() = suspendTransaction {
            AchievementTable.insert {
                it[AchievementTable.playerId] = playerId
                it[AchievementTable.achievementName] = probe
            }
        }
        try {
            insert()
            if (runCatching { insert() }.isSuccess) {
                fail(
                    "player_achievements has no enforced unique index on (player_id, achievement_name) " +
                        "in the database this test reached - ${reachedDatabase()}. It took the same pair " +
                        "twice. setAchievement rests entirely on that refusal, so nothing this test does " +
                        "afterwards would prove anything about it. The indexes that database reports on " +
                        "player_achievements are ${indexesOn("player_achievements")}."
                )
            }
        } finally {
            runCatching { suspendTransaction { AchievementTable.deleteWhere { achievementName eq probe } } }
        }
    }

    /** The same precondition for `createPlayerData`, whose serialisation point is `players.uuid`. */
    private suspend fun assertPlayerUuidIsUnique() {
        val uuid = "uuid-index-probe-${System.nanoTime()}".take(25)
        // players.name carries its own unique index and is varchar(256), so the two probes differ by
        // name: only the uuid index can be what refuses the second one.
        suspend fun insert(name: String) = createPlayerData(name, uuid, name, name)
        try {
            insert("$uuid-a")
            if (runCatching { insert("$uuid-b") }.isSuccess) {
                fail(
                    "players has no enforced unique index on uuid in the database this test reached - " +
                        "${reachedDatabase()}. It took the same uuid twice. createPlayerData rests " +
                        "entirely on that refusal. The indexes that database reports on players are " +
                        "${indexesOn("players")}."
                )
            }
        } finally {
            runCatching { suspendTransaction { PlayerTable.deleteWhere { PlayerTable.uuid eq uuid } } }
        }
    }
}
