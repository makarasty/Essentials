package essential.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TempBanSweepThreadTest {
    /**
     * TempBan.kt:52's comment says the ban list "is read on the main thread and handed to the
     * sweep." Before this fix, `scheduleTick`'s body was `scope.launch { tick(readBanned()) }`:
     * scope.launch dispatches its whole block onto Dispatchers.IO before evaluating any of it,
     * argument expressions included, so the read ran on an IO thread despite being textually
     * inside the function Core.app.post calls on the main thread. This calls scheduleTick()
     * directly - it is the exact body TempBan.start() schedules every 30 seconds, extracted so a
     * test does not have to wait on that Timer - with a probe standing in for localBans(), and
     * pins that the probe runs on the thread that called scheduleTick(), not on the scope's
     * dispatcher.
     */
    @Test
    fun theBanListReadRunsOnTheCallingThreadNotOnTheScopesDispatcher() {
        val callingThread = Thread.currentThread()
        var sawThread: Thread? = null

        TempBan.scheduleTick {
            sawThread = Thread.currentThread()
            emptySet()
        }

        assertEquals(
            callingThread, sawThread,
            "the ban list read must happen on the thread that called scheduleTick(), not on scope's dispatcher"
        )
    }
}
