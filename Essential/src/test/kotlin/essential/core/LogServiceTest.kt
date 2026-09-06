package essential.core

import arc.Core
import arc.Settings
import arc.files.Fi
import arc.util.Log
import essential.common.log.LogType
import essential.common.log.flushLog
import essential.common.log.initLogFiles
import essential.common.log.isLogEnabled
import essential.common.log.onLogWritten
import essential.common.log.stopLogWriter
import essential.common.log.writeLog
import essential.common.rootPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogServiceTest {
    private lateinit var original: CoreConfig

    private val blockLog: Fi
        get() = rootPath.child("log/${LogType.Block}.log")

    private val reportFolder: Fi
        get() = rootPath.child("log/report")

    private val oldBlockFolder: Fi
        get() = rootPath.child("log/old/${LogType.Block}")

    @BeforeTest
    fun setup() {
        if (Core.settings == null) {
            Core.settings = Settings()
            Core.settings.dataDirectory = Fi("")
        }
        original = Main.conf
        initLogFiles()
        blockLog.writeString("")
    }

    @AfterTest
    fun cleanup() {
        onLogWritten = null
        stopLogWriter()
        Main.conf = original
        if (oldBlockFolder.exists() && !oldBlockFolder.isDirectory) oldBlockFolder.delete()
    }

    private fun setLog(block: Boolean = false, report: Boolean = false) {
        Main.conf = Main.conf.copy(
            feature = Main.conf.feature.copy(log = LogFeature(block = block, report = report))
        )
    }

    @Test
    fun startup_creates_every_log_file() {
        for (type in LogType.entries) {
            rootPath.child("log/$type.log").delete()
        }

        initLogFiles()

        for (type in LogType.entries) {
            val file = rootPath.child("log/$type.log")
            assertTrue(file.exists(), "log/$type.log was not created")
        }
    }

    @Test
    fun disabled_group_writes_nothing() {
        setLog(block = false)
        val before = if (blockLog.exists()) blockLog.readString() else null

        writeLog(LogType.Block, "disabled marker ${System.nanoTime()}")
        flushLog()

        val after = if (blockLog.exists()) blockLog.readString() else null
        assertEquals(before, after)
    }

    @Test
    fun disabled_group_does_not_evaluate_lambda() {
        setLog(block = false)
        var evaluated = false

        writeLog(LogType.Block) {
            evaluated = true
            "lambda marker"
        }
        flushLog()

        assertFalse(evaluated)
        assertFalse(isLogEnabled(LogType.Block))
    }

    @Test
    fun enabled_group_evaluates_lambda() {
        setLog(block = true)
        val text = "lambda marker ${System.nanoTime()}"

        writeLog(LogType.Block) { text }
        flushLog()

        assertTrue(blockLog.readString().contains(text))
    }

    @Test
    fun enabled_group_writes_on_logger_thread() {
        setLog(block = true)
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

    @Test
    fun flush_waits_for_the_whole_batch() {
        setLog(block = true)
        val stamp = System.nanoTime()

        repeat(500) { writeLog(LogType.Block, "flush marker $stamp $it") }
        flushLog()

        val content = blockLog.readString()
        repeat(500) { assertTrue(content.contains("flush marker $stamp $it"), "line $it is missing") }
    }

    @Test
    fun shutdown_drains_everything() {
        setLog(block = true)
        val stamp = System.nanoTime()

        repeat(500) { writeLog(LogType.Block, "shutdown marker $stamp $it") }
        stopLogWriter()

        val content = blockLog.readString()
        repeat(500) { assertTrue(content.contains("shutdown marker $stamp $it"), "line $it is missing") }
    }

    @Test
    fun failed_rotation_does_not_spam() {
        setLog(block = true)
        rootPath.child("log/old").mkdirs()
        oldBlockFolder.delete()
        oldBlockFolder.writeString("not a folder")
        blockLog.writeString("x".repeat(3 * 1024 * 1024))

        val warnings = ArrayList<String>()
        val previous = Log.logger
        Log.logger = Log.LogHandler { level, text ->
            if (level == Log.LogLevel.warn || level == Log.LogLevel.err) warnings.add(text)
        }
        try {
            repeat(5) {
                writeLog(LogType.Block, "rotate marker $it")
                flushLog()
            }
        } finally {
            Log.logger = previous
        }

        assertEquals(1, warnings.size, "expected one warning, got $warnings")
        val content = blockLog.readString()
        repeat(5) { assertTrue(content.contains("rotate marker $it"), "line $it is missing") }
        assertFalse(content.contains("end of file"))
    }

    @Test
    fun report_without_a_name_falls_back_to_unknown() {
        setLog(report = true)
        val before = reportFolder.list().map { it.name() }.toSet()

        writeLog(LogType.Report, "report body")
        flushLog()

        val added = reportFolder.list().map { it.name() }.filterNot { before.contains(it) }
        assertEquals(1, added.size, "expected one report file, got $added")
        assertTrue(added.single().endsWith("-unknown.txt"), added.single())
    }

    @Test
    fun two_reports_do_not_overwrite_each_other() {
        setLog(report = true)
        val before = reportFolder.list().map { it.name() }.toSet()

        writeLog(LogType.Report, "first body", "bob")
        writeLog(LogType.Report, "second body", "bob")
        flushLog()

        val added = reportFolder.list().filterNot { before.contains(it.name()) }
        assertEquals(2, added.size, "expected two report files, got ${added.map { it.name() }}")
        assertEquals(setOf("first body", "second body"), added.map { it.readString() }.toSet())
    }
}
