package essential.common.log

import PluginTest.Companion.loadGame
import PluginTest.Companion.waitUntil
import essential.common.rootPath
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The log writer thread has two exits and only the orderly one used to keep the bookkeeping.
 *
 * `running` is both the writer loop's continue condition and the guard `startWriter` checks before it
 * starts a replacement, and `stopLogWriter` clears it before interrupting. The loop's own
 * `InterruptedException` handler did not, so one interrupt from anywhere else - any tooling that
 * interrupts threads it does not own - ended the writer while leaving the flag set, and every later
 * line went into a queue with no consumer until it filled and the log went silent for the rest of the
 * server's life.
 */
class LogWriterInterruptTest {

    private fun writerThread(): Thread? =
        Thread.getAllStackTraces().keys.firstOrNull { it.name == "essential-log" && it.isAlive }

    private fun logFile() = rootPath.child("log/${LogType.Player}.log")

    @Test
    fun anInterruptedWriterIsReplacedAndLoggingCarriesOn() {
        loadGame(true)
        initLogFiles()

        // Starts the writer thread, so there is something to interrupt.
        writeLog(LogType.Player, "before the interrupt")
        assertTrue(waitUntil(10000) { writerThread() != null }, "the writer thread never started")

        val writer = assertNotNull(writerThread(), "the writer thread never started")
        writer.interrupt()
        assertTrue(
            waitUntil(10000) { !writer.isAlive },
            "the interrupt did not end the writer thread, so this test proves nothing"
        )

        // Read back from the file rather than from the queue: the whole defect is that the line is
        // accepted and never written.
        val marker = "after the interrupt ${System.nanoTime()}"
        writeLog(LogType.Player, marker)

        assertTrue(
            waitUntil(15000) { logFile().exists() && logFile().readString().contains(marker) },
            "a line written after the writer was interrupted never reached ${logFile().path()}, " +
                "so one interrupt stopped logging for good"
        )
    }
}
