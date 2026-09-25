package essential.common.database

import arc.util.Log
import essential.common.database.table.WorldHistoryTable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        val kind: String?,
        val uuid: String?,
        val createdAt: Long,
    )

    private const val MAX_BATCH_SIZE = 100
    private const val FLUSH_INTERVAL_MS = 200L

    /**
     * How many rows may be waiting to be written before the oldest are dropped.
     *
     * The queue used to be unbounded while its producer is `tap()` on the game thread, so a database
     * that stalled grew it until the server ran out of heap. Blocking the producer instead is not
     * available: that would stall the game thread behind the database. So the buffer keeps the newest
     * rows and drops the oldest, which is the right direction for rollback history - the recent edits
     * are the ones an admin is about to undo.
     *
     * 20000 rows is a few megabytes and roughly forty seconds of one player building flat out.
     */
    internal const val QUEUE_CAPACITY = 20000
    private const val WARN_INTERVAL_MS = 60000L

    // A deque rather than a queue so a batch whose write failed can go back at the head it came from.
    // Putting it at the tail reordered it behind rows recorded later and, worse, made the failing batch
    // the last thing the drop-oldest policy would ever evict - the exact inversion of what that policy
    // is for.
    private val queue = LinkedBlockingDeque<PendingInsert>(QUEUE_CAPACITY)
    private val flushMutex = Mutex()
    private val stopped = AtomicBoolean(false)

    private val dropped = AtomicLong()
    private val lastDropWarn = AtomicLong()
    private val lastFlushWarn = AtomicLong()

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

    fun getLastBlock(x: Short, y: Short): String? {
        val packed = (x.toInt() shl 16) or (y.toInt() and 0xFFFF)
        return lastBlockCache[packed]
    }

    fun start(scope: CoroutineScope) {
        if (flushJob != null && !flushJob!!.isCancelled) return
        stopped.set(false)
        // No reload of the table into lastBlockCache first: rows left from before a restart belong to
        // a world that is gone, and the first WorldLoadEvent truncates them anyway. Reading them all in
        // was a full-table load into the heap on every start, racing that truncate.
        flushJob = scope.launch(Dispatchers.IO) { flushLoop() }
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
        // Defaulted, so a caller that has no type or no acting player in hand - and every caller written
        // before these columns existed - records null rather than being forced to invent one. Null is
        // also what every row already in the table carries, so the two cases are one case downstream.
        kind: String? = null,
        uuid: String? = null,
    ) {
        if (stopped.get()) return
        val packed = (x.toInt() shl 16) or (y.toInt() and 0xFFFF)
        lastBlockCache[packed] = tile
        offerNewest(
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
                kind = kind,
                uuid = uuid,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Adds [entry], making room by discarding the oldest queued row when the buffer is full.
     *
     * Never blocks: the hot-path caller is the tile listener on the game thread, and the flush loop on
     * Dispatchers.IO reaches the same deque, so the two offers below can genuinely race. The loss is
     * reported with a running total rather than once per row, because a stalled database drops rows as
     * fast as players place blocks.
     */
    private fun offerNewest(entry: PendingInsert) {
        if (queue.offerLast(entry)) return

        val evicted = queue.pollFirst()
        if (!queue.offerLast(entry)) {
            // A concurrent writer took the slot that poll freed, so this row is lost as well as the one
            // evicted to make room for it. Counted as both: a total an operator cannot trust is worse
            // than no total.
            reportDropped(if (evicted != null) 2 else 1)
            return
        }
        // poll can also come back empty, when a flush drained the queue between the two offers. Then
        // nothing was lost and nothing is counted.
        if (evicted != null) reportDropped(1)
    }

    /** Adds [count] to the running loss and says so at most once per [WARN_INTERVAL_MS]. */
    private fun reportDropped(count: Int) {
        val total = dropped.addAndGet(count.toLong())
        if (allowedNow(lastDropWarn)) {
            Log.warn("[WorldHistoryBuffer] buffer full, dropped $total oldest entries; rollback history is incomplete")
        }
    }

    /**
     * True at most once per [WARN_INTERVAL_MS] per gate.
     *
     * Both things this rate-limits fire per row or per flush tick: a stalled database drops rows as fast
     * as players place blocks, and the flush loop retries five times a second for as long as it stays
     * down. Logging either unthrottled floods the console and fills the log service queue, which then
     * starts dropping the lines that would have said so.
     */
    private fun allowedNow(gate: AtomicLong): Boolean {
        val now = System.currentTimeMillis()
        val last = gate.get()
        return now - last >= WARN_INTERVAL_MS && gate.compareAndSet(last, now)
    }

    /**
     * Puts a batch whose write did not happen back at the head, in the order it was drained.
     *
     * What no longer fits is the oldest history in the buffer, which is what the bound discards anyway,
     * so a database that stays down converges on keeping the newest [QUEUE_CAPACITY] rows rather than
     * on preserving the one batch that keeps failing.
     */
    private fun requeueOldest(batch: List<PendingInsert>) {
        var lost = 0
        for (entry in batch.asReversed()) {
            if (!queue.offerFirst(entry)) lost++
        }
        if (lost > 0) reportDropped(lost)
    }

    private fun drain(): List<PendingInsert> {
        val batch = ArrayList<PendingInsert>(MAX_BATCH_SIZE)
        // Capped so one transaction stays one transaction rather than becoming however much the queue
        // grew to while the database was away. flushAll is what puts the whole backlog back together.
        queue.drainTo(batch, MAX_BATCH_SIZE)
        return batch
    }

    /**
     * Writes queued rows until the queue is empty or a write fails.
     *
     * Bounded by what was waiting when the caller took the lock. The contract [flush] offers is that
     * everything recorded before the call reaches the table, not that the buffer is empty afterwards,
     * and the producer is the game thread - without the bound a server building steadily could keep
     * this loop, and [flushMutex] with it, going for as long as people keep placing blocks.
     *
     * Stops on the first failure rather than retrying, because [flushBatch] has put that batch back at
     * the head and the next drain would take the same rows straight out again.
     *
     * The caller holds [flushMutex].
     */
    private suspend fun flushAll() {
        var remaining = queue.size + MAX_BATCH_SIZE
        while (remaining > 0) {
            val batch = drain()
            if (batch.isEmpty()) return
            if (!flushBatch(batch)) return
            remaining -= batch.size
        }
    }

    /**
     * Takes the periodic flush's own lock, which is what makes this usable as "the table now holds
     * everything recorded up to here": without it a batch the periodic flush had already drained could
     * still be inside its transaction, and its rows would land after a caller had emptied the table.
     *
     * That reading holds only while the writes succeed. A failing write stops the drain and leaves its
     * rows in the buffer, and rows the bound has already discarded are not coming back, so a caller
     * that rebuilds the world from the table can act on less than it asked for. It has no way to be
     * told: this returns Unit, and widening it would reach into files this change does not own.
     */
    suspend fun flush() {
        flushMutex.withLock { flushAll() }
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
            flushMutex.withLock { flushAll() }
        }
    }

    /**
     * Writes one batch, and hands it back to the queue if the write did not happen.
     *
     * `drain` removes the rows from the only structure holding them, so a failure used to lose that
     * batch for good: one `flush failed` line and the record of who placed those blocks was gone while
     * the map still showed them. Re-queueing costs the possibility of a duplicate row - a write that
     * committed and then failed on the way home is written twice - and world_history has no unique key
     * to stop that. A duplicated rollback entry is recoverable; a missing one is not.
     *
     * Cancellation lands here too, which is what closes the shutdown hole: [stop] can cancel the flush
     * loop while a batch is inside its transaction, and the rows go back into the queue in time for the
     * `NonCancellable` flush that follows.
     *
     * @return true when the rows are in the table.
     */
    private suspend fun flushBatch(batch: List<PendingInsert>): Boolean {
        if (batch.isEmpty()) return true
        return runCatching {
            suspendTransaction(db = worldHistoryDatabase) {
                // One batched statement per flush rather than one INSERT per row, and no generated ids
                // read back: nothing here uses them.
                WorldHistoryTable.batchInsert(batch, shouldReturnGeneratedValues = false) { e ->
                    this[WorldHistoryTable.time] = e.time
                    this[WorldHistoryTable.player] = e.player
                    this[WorldHistoryTable.action] = e.action
                    this[WorldHistoryTable.x] = e.x
                    this[WorldHistoryTable.y] = e.y
                    this[WorldHistoryTable.tile] = e.tile
                    this[WorldHistoryTable.rotate] = e.rotate
                    this[WorldHistoryTable.team] = e.team
                    this[WorldHistoryTable.value] = e.value
                    this[WorldHistoryTable.kind] = e.kind
                    this[WorldHistoryTable.uuid] = e.uuid
                    this[WorldHistoryTable.createdAt] = Instant.fromEpochMilliseconds(e.createdAt)
                }
            }
            Log.debug("[WorldHistoryBuffer] flushed ${batch.size} entries")
        }.onFailure { cause ->
            // Cancellation is not a failure worth an error line: it is what a clean shutdown looks like
            // from in here, and the NonCancellable flush in stop() writes these rows a moment later.
            // Anything else is rate-limited, because the loop retries five times a second and a
            // database that is down stays down.
            when {
                cause is CancellationException ->
                    Log.debug("[WorldHistoryBuffer] flush cancelled, ${batch.size} entries requeued")

                allowedNow(lastFlushWarn) ->
                    Log.err("[WorldHistoryBuffer] flush failed, ${batch.size} entries requeued", cause)
            }
            requeueOldest(batch)
        }.isSuccess
    }
}
