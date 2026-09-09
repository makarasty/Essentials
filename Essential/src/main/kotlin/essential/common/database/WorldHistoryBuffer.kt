package essential.common.database

import arc.util.Log
import essential.common.database.data.getAllWorldHistory
import essential.common.database.table.WorldHistoryTable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
object WorldHistoryBuffer {

    private data class PendingInsert(
        val time: Long,
        val player: String,
        val action: String,
        val x: Short,
        val y: Short,
        val tile: String,
        val rotate: Int,
        val team: String,
        val value: String?,
        val createdAt: Long,
    )

    private const val MAX_BATCH_SIZE = 100
    private const val FLUSH_INTERVAL_MS = 200L

    private val queue = LinkedBlockingQueue<PendingInsert>()
    private val flushMutex = Mutex()
    private val stopped = AtomicBoolean(false)

    private var flushJob: Job? = null

    private val lastBlockCache = ConcurrentHashMap<Int, String>()

    /**
     * Empties the table and the buffer as one step under the flush lock, so nothing recorded before the
     * call can reach the table after it. Clearing the cache alone left `queue` pending, and the next
     * flush tick inserted those rows into the table that had just been truncated.
     *
     * The truncate goes first so a failing one leaves both the table and the buffer as they were.
     */
    suspend fun discard() {
        flushMutex.withLock {
            suspendTransaction(db = worldHistoryDatabase) {
                exec("TRUNCATE TABLE world_history")
            }
            queue.clear()
            lastBlockCache.clear()
        }
    }

    suspend fun reload() {
        runCatching {
            val existing = getAllWorldHistory()
            val newCache = ConcurrentHashMap<Int, String>()
            existing.sortedBy { it.time }.forEach { entry ->
                val packed = (entry.x.toInt() shl 16) or (entry.y.toInt() and 0xFFFF)
                newCache[packed] = entry.tile
            }
            lastBlockCache.clear()
            lastBlockCache.putAll(newCache)
        }.onFailure {
            Log.err("[WorldHistoryBuffer] Failed to reload history into cache", it)
        }
    }

    fun getLastBlock(x: Short, y: Short): String? {
        val packed = (x.toInt() shl 16) or (y.toInt() and 0xFFFF)
        return lastBlockCache[packed]
    }

    fun start(scope: CoroutineScope) {
        if (flushJob != null && !flushJob!!.isCancelled) return
        stopped.set(false)
        flushJob = scope.launch(Dispatchers.IO) {
            reload()
            flushLoop()
        }
    }

    fun enqueue(
        time: Long,
        player: String,
        action: String,
        x: Short,
        y: Short,
        tile: String,
        rotate: Int,
        team: String,
        value: String?,
    ) {
        if (stopped.get()) return
        val packed = (x.toInt() shl 16) or (y.toInt() and 0xFFFF)
        lastBlockCache[packed] = tile
        queue.add(
            PendingInsert(
                time = time,
                player = player,
                action = action,
                x = x,
                y = y,
                tile = tile,
                rotate = rotate,
                team = team,
                value = value,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    private fun drain(): List<PendingInsert> {
        val batch = ArrayList<PendingInsert>(MAX_BATCH_SIZE)
        queue.drainTo(batch)
        return batch
    }

    /**
     * Takes the periodic flush's own lock, which is what makes this usable as "the table now holds
     * everything recorded up to here": without it a batch the periodic flush had already drained could
     * still be inside its transaction, and its rows would land after a caller had emptied the table.
     */
    suspend fun flush() {
        flushMutex.withLock {
            val batch = drain()
            if (batch.isNotEmpty()) flushBatch(batch)
        }
    }

    suspend fun stop() {
        stopped.set(true)
        flushJob?.cancelAndJoin()
        flushJob = null
        // cancelAndJoin has already stopped the flush loop, but discard() is a second writer shutdown
        // does not otherwise coordinate with: draining outside the lock here would take the rows a map
        // change was about to clear and commit them after its truncate.
        withContext(NonCancellable) { flush() }
    }

    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_INTERVAL_MS.milliseconds)
            if (stopped.get()) break
            if (queue.isEmpty()) continue
            flushMutex.withLock {
                val batch = drain()
                if (batch.isNotEmpty()) flushBatch(batch)
            }
        }
    }

    private suspend fun flushBatch(batch: List<PendingInsert>) {
        if (batch.isEmpty()) return
        runCatching {
            suspendTransaction(db = worldHistoryDatabase) {
                batch.forEach { e ->
                    WorldHistoryTable.insert { row ->
                        row[WorldHistoryTable.time] = e.time
                        row[WorldHistoryTable.player] = e.player
                        row[WorldHistoryTable.action] = e.action
                        row[WorldHistoryTable.x] = e.x
                        row[WorldHistoryTable.y] = e.y
                        row[WorldHistoryTable.tile] = e.tile
                        row[WorldHistoryTable.rotate] = e.rotate
                        row[WorldHistoryTable.team] = e.team
                        row[WorldHistoryTable.value] = e.value
                        row[WorldHistoryTable.createdAt] = Instant.fromEpochMilliseconds(e.createdAt)
                    }
                }
            }
            Log.debug("[WorldHistoryBuffer] flushed ${batch.size} entries")
        }.onFailure {
            Log.err("[WorldHistoryBuffer] flush failed", it)
        }
    }
}
