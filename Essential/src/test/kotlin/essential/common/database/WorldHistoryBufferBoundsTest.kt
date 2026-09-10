package essential.common.database

import PluginTest.Companion.expectingErrors
import PluginTest.Companion.loadGame
import PluginTest.Companion.waitUntil
import arc.util.Log
import essential.common.database.data.getAllWorldHistory
import essential.common.database.table.WorldHistoryTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two halves of the world-history buffer that pull against each other: it must not lose rows a
 * write failed on, and it must not grow without limit while that failure lasts.
 *
 * The failure is produced by dropping `world_history` out from under the buffer, which is the closest
 * this suite can get to the reported condition - a locked file, an exhausted pool, a database that
 * stopped answering. What matters is only that the insert throws.
 */
class WorldHistoryBufferBoundsTest {

    private val warnings = CopyOnWriteArrayList<String>()
    private var previousLogger: Log.LogHandler? = null

    @BeforeTest
    fun setup() {
        loadGame(true)
        runBlocking { WorldHistoryBuffer.discard() }
    }

    @AfterTest
    fun teardown() {
        // Table first, logger last. The flush loop runs every FLUSH_INTERVAL_MS (200 ms) on
        // Dispatchers.IO and `aStalledDatabaseBoundsTheQueueAndSaysSoOnce` leaves 20050 rows queued
        // against a dropped table, so restoring the guard before the table put a live guard in front
        // of a tick that is guaranteed to fail. It never fired - the test and its teardown take about
        // 28 ms, inside one tick - but that is timing, not correctness, and this box runs several
        // sessions at once. When it did fire the throw would land on the IO worker, fail nothing, and
        // skip `requeueOldest` in WorldHistoryBuffer.flushBatch, destroying the batch this class
        // exists to prove is kept.
        runCatching { runBlocking { restoreTable() } }
        runCatching { runBlocking { WorldHistoryBuffer.discard() } }
        previousLogger?.let { Log.logger = it }
        previousLogger = null
    }

    private suspend fun dropTable() = suspendTransaction(db = worldHistoryDatabase) {
        exec("DROP TABLE IF EXISTS world_history")
    }

    private suspend fun restoreTable() = suspendTransaction(db = worldHistoryDatabase) {
        SchemaUtils.create(WorldHistoryTable)
    }

    private fun record(player: String, x: Short, y: Short = 1) = WorldHistoryBuffer.enqueue(
        time = System.currentTimeMillis(),
        player = player,
        action = "place",
        x = x,
        y = y,
        tile = "copper-wall",
        rotate = 0,
        team = "sharded",
        value = null
    )

    @Test
    fun aFailedWriteKeepsItsRowsForTheNextFlush() {
        // The error this test exists to cause is now the harness's business too: `stopPlugin`
        // reinstalls the log guard, so `[WorldHistoryBuffer] flush failed` would fail the test that
        // deliberately provokes it. Declared rather than silenced - every other error line still
        // throws inside the block.
        //
        // The allowance spans the drop through the restore rather than the forced flush alone, so
        // that a flush loop tick landing while the table is gone is covered too.
        //
        // Be careful reading a green here as proof this ran. `flushBatch` gates that line behind
        // `allowedNow(lastFlushWarn)` - WARN_INTERVAL_MS is 60000 against a process-global AtomicLong
        // (WorldHistoryBuffer.kt:50, :62, :313) - so the whole JVM emits it at most once a minute.
        // Whether this test provokes it at all depends on what earlier classes already spent that
        // budget on, which makes the underlying failure order-dependent rather than deterministic.
        expectingErrors("[WorldHistoryBuffer] flush failed") {
            runBlocking { dropTable() }

            record("kept", 11)
            // Fails: the table is not there. The rows have already left the queue by then, and used to
            // exist only as a local list that went out of scope with the log line.
            runBlocking { WorldHistoryBuffer.flush() }

            runBlocking { restoreTable() }
            runBlocking { WorldHistoryBuffer.flush() }
        }

        val rows = runBlocking { getAllWorldHistory() }
        assertEquals(
            1, rows.count { it.player == "kept" },
            "a row whose write failed was dropped instead of being written by the next flush"
        )
    }

    @Test
    fun aStalledDatabaseBoundsTheQueueAndSaysSoOnce() {
        previousLogger = Log.logger
        // PluginTest's handler turns Log.err into a throw, and a stalled flush logs err every tick.
        Log.logger = Log.LogHandler { _, text -> if (text.contains("buffer full")) warnings += text }

        runBlocking { dropTable() }

        // One row past the ceiling. Nothing can leave the queue while the table is gone, so the only
        // thing that stops this growing is the bound.
        repeat(WorldHistoryBuffer.QUEUE_CAPACITY + 50) { record("flood", 12) }

        assertTrue(
            waitUntil(10000) { warnings.isNotEmpty() },
            "the queue took ${WorldHistoryBuffer.QUEUE_CAPACITY + 50} rows against a ceiling of " +
                "${WorldHistoryBuffer.QUEUE_CAPACITY} without dropping any, so it is still unbounded"
        )
        assertTrue(
            warnings.first().contains("dropped"),
            "the warning does not say how much was lost: ${warnings.first()}"
        )
    }
}
