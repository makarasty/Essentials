package essential.core

import PluginTest.Companion.loadGame
import arc.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * task-158: init() and dispose() each used to call a suspend function directly inside their own
 * runBlocking, with nothing bounding either. Both run on the main thread (init() from the
 * engine's Mod::init pass, dispose() from Core.app's own dispose pass), so an unreachable
 * database at boot, or forty online players' worth of shutdown saves, could hang the whole
 * server with no log line explaining why. These call Main.initDatabaseWithTimeout and
 * Main.saveOnShutdownWithTimeout directly - the exact functions init() and dispose() call - with
 * a suspend function that never completes standing in for the real database call, so the test
 * proves the timeout wiring in under a second instead of waiting out the real 30-second
 * production timeout.
 */
class DatabaseInitTimeoutTest {
    companion object {
        private var done = false
    }

    // Touching Main.initDatabaseWithTimeout/saveOnShutdownWithTimeout forces Main's companion
    // object to initialise, which reads config through rootPath - same reason
    // ScopeExceptionHandlerTest needs this.
    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    /**
     * PluginTest's own ApplicationCore.setup() installs a logger that throws a RuntimeException
     * on any err-level line, by design, so an unexpected error surfaces loudly during setup - but
     * both functions under test call Log.err deliberately, so that logger has to be swapped out
     * for the duration of the call, the same trap ScopeExceptionHandlerTest hit.
     */
    private fun <T> withoutThrowingLogger(block: () -> T): T {
        val previous = Log.logger
        Log.logger = Log.LogHandler { _, _ -> }
        try {
            return block()
        } finally {
            Log.logger = previous
        }
    }

    @Test
    fun initDatabaseWithTimeoutCutsOffAConnectThatNeverCompletes() = withoutThrowingLogger {
        assertFailsWith<TimeoutCancellationException> {
            runBlocking {
                Main.initDatabaseWithTimeout(100L, "unreachable-host-for-test") {
                    awaitCancellation()
                }
            }
        }
        Unit
    }

    @Test
    fun saveOnShutdownWithTimeoutReturnsFalseInsteadOfBlockingForever() = withoutThrowingLogger {
        val finished = runBlocking {
            Main.saveOnShutdownWithTimeout(100L) {
                awaitCancellation()
            }
        }
        assertFalse(finished, "a save that never completes should report false, not hang dispose() forever")
    }

    @Test
    fun saveOnShutdownWithTimeoutReturnsTrueWhenTheSaveActuallyFinishes() {
        val finished = runBlocking {
            Main.saveOnShutdownWithTimeout(5_000L) {
                // completes immediately
            }
        }
        assertTrue(finished, "a save that finishes well inside the timeout should report true")
    }
}
