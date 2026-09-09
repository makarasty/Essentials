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
 * **Both also count how many of their attempts genuinely met a refusal**, so a run in which the two
 * calls happened to be serialised - and which therefore never reached the branch under test - is red
 * rather than a vacuous green. That is not decoration: in the full suite, before the harness stopped
 * handing this class a legacy-shaped database, [twoServersCreatingOnePlayerBothGetTheRow] passed on
 * zero refusals in twenty-five attempts against a `players` table with no unique index on `uuid` at
 * all. The counts come from the two functions' own log lines and nothing else in this class emits
 * either. `Log.logger` is a JVM global and the loaded plugin writes through the same handler, so the
 * counts are not sealed off absolutely - but nothing in this process reaches those branches without a
 * genuine refusal, and an inflated count could only ever weaken the gate towards a pass.
 *
 * An earlier counter inferred the number of inserts that reached the engine from the gap in the
 * auto-increment column, since a refused insert has already taken its id. It reported 38 and 56 races
 * out of 25 attempts, because H2 allocates identity values in cached blocks and the gaps are the
 * cache. Do not reach for it again.
 */
class ConcurrentInsertRaceTest {

    private companion object {
        const val ATTEMPTS = 25

        /**
         * The ceiling on the attempt loop. The invariant is asserted on every attempt; the extra ones
         * exist only so the fire-rate gate is not a coin toss. Measured rates are 7 to 19 in 25, so
         * [ATTEMPTS] alone would miss entirely about three times in ten thousand - small, but this
         * class is the gate on a suite that must not gain a flake, and the rate is scheduler-dependent
         * on a machine running several builds at once.
         */
        const val MAX_ATTEMPTS = 200

        /** What each function says when the engine refused its insert. Neither is a prefix of the other. */
        const val PLAYER_REFUSAL = "Insert refused for"
        const val ACHIEVEMENT_REFUSAL = "Achievement insert refused for"
    }

    private val playerRefusals = AtomicInteger()
    private val achievementRefusals = AtomicInteger()
    private var previousLogger: Log.LogHandler? = null

    @BeforeTest
    fun setup() {
        loadGame(true)
        // Delegating rather than replacing: loadGame's handler turns a Log.err into a failure, and a
        // counter that quietly took that away would hide the failures this class exists to catch.
        val previous = Log.logger
        previousLogger = previous
        Log.logger = Log.LogHandler { level, text ->
            when {
                text.contains(ACHIEVEMENT_REFUSAL) -> achievementRefusals.incrementAndGet()
                text.contains(PLAYER_REFUSAL) -> playerRefusals.incrementAndGet()
            }
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
        var attempts = 0
        while (attempts < ATTEMPTS || (raced == 0 && attempts < MAX_ATTEMPTS)) {
            attempts++
            val player = createPlayer()
            val before = playerRefusals.get()
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
                if (playerRefusals.get() > before) raced++
            } finally {
                player.remove()
                suspendTransaction { PlayerTable.deleteWhere { uuid eq player.uuid() } }
            }
        }

        println("[race] createPlayerData met a refusal on $raced of $attempts attempts, against ${reachedDatabase()}")
        assertTrue(raced > 0, neverRaced(attempts, "createPlayerData"))
    }

    @Test
    fun twoServersAwardingOneAchievementBothSucceed() = runBlocking {
        val player = createPlayer()
        val data = createPlayerData(player)
        try {
            assertAchievementPairIsUnique(data.id)

            var raced = 0
            var attempt = 0
            while (attempt < ATTEMPTS || (raced == 0 && attempt < MAX_ATTEMPTS)) {
                val name = "race-achievement-$attempt"
                attempt++
                val before = achievementRefusals.get()
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
                if (achievementRefusals.get() > before) raced++
            }

            println("[race] setAchievement met a refusal on $raced of $attempt attempts, against ${reachedDatabase()}")
            assertTrue(raced > 0, neverRaced(attempt, "setAchievement"))
        } finally {
            suspendTransaction { AchievementTable.deleteWhere { playerId eq data.id } }
            suspendTransaction { PlayerTable.deleteWhere { uuid eq player.uuid() } }
            player.remove()
        }
    }

    private fun neverRaced(attempts: Int, function: String) =
        "the two callers were serialised on all $attempts attempts, so not one of them met a refusal " +
            "and this test never reached the branch in $function that tolerates one. It is not a " +
            "timeout: the invariant held every time, and the race simply never fired, so a pass here " +
            "would have meant nothing. Against ${reachedDatabase()}."

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
            // Guarded, because the diagnostic below is the whole point of this function and a raw stack
            // trace out of the first insert - a probe row a dead run left behind, a connection blip -
            // would replace it with nothing anybody can act on.
            runCatching { insert() }.onFailure {
                fail(
                    "the first achievement probe insert was itself refused in ${reachedDatabase()}: " +
                        "${it.message}. That is not the missing index this checks for; something else " +
                        "is wrong with player_achievements, whose indexes are " +
                        "${indexesOn("player_achievements")}."
                )
            }
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
        // players.uuid is varchar(25), and the prefix is short on purpose: with a longer one the take()
        // would keep only the high-order digits of nanoTime, which change every hundred seconds or so,
        // and a probe left behind by a run that died would be refused here and read as a missing index.
        val uuid = "uip-${System.nanoTime()}".take(25)
        // players.name carries its own unique index and is varchar(256), so the two probes differ by
        // name: only the uuid index can be what refuses the second one.
        suspend fun insert(name: String) = createPlayerData(name, uuid, name, name)
        try {
            runCatching { insert("$uuid-a") }.onFailure {
                fail(
                    "the first uuid probe insert was itself refused in ${reachedDatabase()}: " +
                        "${it.message}. That is not the missing index this checks for; something else " +
                        "is wrong with players, whose indexes are ${indexesOn("players")}."
                )
            }
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
