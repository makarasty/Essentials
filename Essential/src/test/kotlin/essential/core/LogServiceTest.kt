package essential.core

import arc.Core
import arc.Settings
import arc.files.Fi
import essential.common.log.LogType
import essential.common.log.flushLog
import essential.common.log.onLogWritten
import essential.common.log.stopLogWriter
import essential.common.log.writeLog
import essential.common.rootPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogServiceTest {
    private lateinit var original: CoreConfig

    private val blockLog: Fi
        get() = rootPath.child("log/${LogType.Block}.log")

    @BeforeTest
    fun setup() {
        if (Core.settings == null) {
            Core.settings = Settings()
            Core.settings.dataDirectory = Fi("")
        }
        original = Main.conf
    }

    @AfterTest
    fun cleanup() {
        onLogWritten = null
        stopLogWriter()
        Main.conf = original
    }

    private fun setBlockLogging(enabled: Boolean) {
        Main.conf = Main.conf.copy(
            feature = Main.conf.feature.copy(log = LogFeature(block = enabled))
        )
    }

    @Test
    fun disabled_group_writes_nothing() {
        setBlockLogging(false)
        val before = if (blockLog.exists()) blockLog.readString() else null

        writeLog(LogType.Block, "disabled marker ${System.nanoTime()}")
        flushLog()

        val after = if (blockLog.exists()) blockLog.readString() else null
        assertEquals(before, after)
    }

    @Test
    fun enabled_group_writes_on_logger_thread() {
        setBlockLogging(true)
        val text = "enabled marker ${System.nanoTime()}"
        val writerThread = arrayOfNulls<Thread>(1)
        onLogWritten = { _, _ -> writerThread[0] = Thread.currentThread() }

        writeLog(LogType.Block, text)
        flushLog()

        assertTrue(blockLog.exists())
        assertTrue(blockLog.readString().contains(text))

        val thread = assertNotNull(writerThread[0])
        assertEquals("essential-log", thread.name)
        assertTrue(thread !== Thread.currentThread())
    }
}
