package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.waitUntil
import arc.util.Log
import kotlinx.coroutines.launch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ScopeExceptionHandlerTest {
    companion object {
        private var done = false
    }

    // Main.scope is a companion val, so touching it forces Main's companion object to
    // initialise - which reads config through rootPath, which needs Core.settings already set
    // up. Standalone (no other class in the same JVM having called loadGame first), that blows
    // up with a NullPointerException out of PluginData.kt's rootPath before this test's own body
    // ever runs.
    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    /**
     * task-075: Main.scope used to be CoroutineScope(SupervisorJob() + Dispatchers.IO) with no
     * CoroutineExceptionHandler. An exception thrown inside `scope.launch { ... }` - a pool
     * acquire timeout, a connection drop, a constraint violation; CoreEvent.kt:727/:1023/:1357's
     * `scope.launch { data.update() }` calls, none of them awaited or wrapped in a try/catch -
     * reached the JVM's default uncaught-exception handler instead of this plugin's own log, and
     * whatever the launch was doing (almost always saving a player's data) was lost with no line
     * in the Essential log connecting the loss to its cause.
     */
    @Test
    fun anExceptionThrownInsideScopeLaunchReachesThePluginLog() {
        val marker = "sentinel for anExceptionThrownInsideScopeLaunchReachesThePluginLog"
        val lines = mutableListOf<String>()
        val previous = Log.logger
        // Not forwarding to `previous`: PluginTest's own ApplicationCore.setup() installs a
        // logger that throws a RuntimeException on any err-level line (by design, so an
        // unexpected error surfaces loudly during setup) - exactly the level this test's
        // exception handler logs at. Forwarding first would throw before `lines.add` ever runs,
        // on the IO thread the handler itself runs on, and this test would see an empty capture
        // no matter what Main.kt does.
        Log.logger = Log.LogHandler { _, text ->
            lines.add(text)
        }
        try {
            Main.scope.launch { throw IllegalStateException(marker) }

            assertTrue(
                waitUntil(5000) { lines.any { it.contains(marker) } },
                "an exception thrown inside scope.launch { } should reach the plugin's own log, but got: $lines"
            )
        } finally {
            Log.logger = previous
        }
    }
}
