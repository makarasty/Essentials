package essential.core.service.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GameThreadTest {
    companion object {
        private var loaded = false
    }

    @BeforeTest
    fun setup() {
        if (!loaded) {
            PluginTest.loadGame(true)
            loaded = true
        }
    }

    /** Starts [block] off the pumping thread, then drains posted runnables here until it finishes. */
    private fun <T> offThread(block: suspend () -> T): Deferred<T> = CoroutineScope(Dispatchers.Default).async { block() }

    private fun drainUntilDone(deferred: Deferred<*>): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
            PluginTest.pumpApp()
            Thread.sleep(5)
        }
        return deferred.isCompleted
    }

    @Test
    fun the_block_runs_on_the_thread_that_owns_the_game_and_returns_its_value() {
        val gameThread = Thread.currentThread()
        val deferred = offThread { onGameThread { Thread.currentThread() to 42 } }

        assertTrue(drainUntilDone(deferred), "onGameThread never completed")
        val (ranOn, value) = runBlocking { deferred.await() }

        assertSame(gameThread, ranOn, "the block ran off the game thread")
        assertEquals(42, value)
    }

    @Test
    fun a_failure_in_the_block_reaches_the_caller() {
        val deferred = offThread { onGameThread { error("boom") } }

        assertTrue(drainUntilDone(deferred), "onGameThread never completed")
        assertFails { runBlocking { deferred.await() } }
    }
}
