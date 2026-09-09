package essential.common.database

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
        previousLogger?.let { Log.logger = it }
        previousLogger = null
        runCatching { runBlocking { restoreTable() } }
        runCatching { runBlocking { WorldHistoryBuffer.discard() } }
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
        runBlocking { dropTable() }

        record("kept", 11)
        // Fails: the table is not there. The rows have already left the queue by then, and used to
        // exist only as a local list that went out of scope with the log line.
        runBlocking { WorldHistoryBuffer.flush() }

        runBlocking { restoreTable() }
        runBlocking { WorldHistoryBuffer.flush() }

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
