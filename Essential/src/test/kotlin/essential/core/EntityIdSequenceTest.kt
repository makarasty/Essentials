package essential.core

import essential.common.playerNumber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-067: entityId used to be `val entityId = playerNumber` (a plain, unsynchronised Int) read at
 * PlayerData construction time - inside the join coroutine - while the increment ran later, on the
 * game thread, in a different file. Two constructions that interleaved before either increment
 * produced equal entityIds, and every '#<id>' lookup (including /votekick) then resolved to
 * whichever of the two collided players sorted first.
 *
 * playerNumber is now an AtomicInteger and PlayerData.entityId reads it via getAndIncrement() at
 * construction, so the id is handed out as one atomic operation with no separate increment step to
 * race against. This drives many concurrent callers at the same instant, the way two players joining
 * within the same second used to, and checks none of them ever gets the same id twice.
 */
class EntityIdSequenceTest {
    @Test
    fun concurrentAllocationsNeverCollide() {
        val threads = 64
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val ids = CopyOnWriteArrayList<Int>()

        repeat(threads) {
            pool.submit {
                ready.countDown()
                start.await()
                ids.add(playerNumber.getAndIncrement())
                done.countDown()
            }
        }

        ready.await()
        start.countDown()
        done.await()
        pool.shutdown()

        assertEquals(
            threads,
            ids.toSet().size,
            "every concurrent allocation must be unique - a duplicate here is the entityId collision task-067 reported"
        )
    }
}
