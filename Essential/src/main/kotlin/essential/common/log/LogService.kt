package essential.common.log

import arc.util.Log
import essential.common.rootPath
import essential.core.Main
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

private const val QUEUE_CAPACITY = 4096
private const val MAX_LOG_SIZE = 2048L * 1024L
private const val MAX_LOG_FILE = 20
private const val DROP_WARN_INTERVAL = 60000L

private data class LogLine(val type: LogType, val text: String, val time: String, val name: String?)

private val queue = ArrayBlockingQueue<LogLine>(QUEUE_CAPACITY)
private val logFiles = HashMap<LogType, FileAppender>()
private val running = AtomicBoolean(false)
private val dropped = AtomicLong()
private val lastDropWarn = AtomicLong()
private val writeLock = Any()
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH_mm_ss")

private var writer: Thread? = null

internal var onLogWritten: ((LogType, String) -> Unit)? = null

fun writeLog(type: LogType, text: String, vararg name: String) {
    if (!isLogEnabled(type)) return

    val line = LogLine(type, text, timeFormat.format(LocalDateTime.now()), name.firstOrNull())
    startWriter()

    if (!queue.offer(line)) {
        val total = dropped.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastDropWarn.get()
        if (now - last >= DROP_WARN_INTERVAL && lastDropWarn.compareAndSet(last, now)) {
            Log.warn("[Essentials] Log queue is full, dropped $total lines")
        }
    }
}

private fun isLogEnabled(type: LogType): Boolean {
    val log = Main.conf.feature.log
    return when (type) {
        LogType.Player -> log.player
        LogType.Chat -> log.chat
        LogType.Report -> log.report
        LogType.Block -> log.block
        LogType.Tap -> log.tap
        LogType.Deposit, LogType.WithDraw -> log.item
        else -> log.other
    }
}

private fun startWriter() {
    if (!running.compareAndSet(false, true)) return
    writer = thread(name = "essential-log", isDaemon = true) {
        while (running.get()) {
            try {
                val first = queue.poll(1, TimeUnit.SECONDS) ?: continue
                val batch = ArrayList<LogLine>()
                batch.add(first)
                queue.drainTo(batch)
                writeBatch(batch)
            } catch (_: InterruptedException) {
                break
            }
        }

        val rest = ArrayList<LogLine>()
        queue.drainTo(rest)
        if (rest.isNotEmpty()) writeBatch(rest)

        synchronized(writeLock) {
            logFiles.values.forEach { runCatching { it.close() } }
            logFiles.clear()
        }
    }
}

internal fun stopLogWriter() {
    if (!running.compareAndSet(true, false)) return
    writer?.let {
        it.interrupt()
        it.join(2000)
    }
    writer = null
}

internal fun flushLog() {
    val deadline = System.currentTimeMillis() + 3000
    while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(5)
    }
    synchronized(writeLock) { }
}

private fun writeBatch(batch: List<LogLine>) = synchronized(writeLock) {
    for (line in batch) {
        runCatching {
            if (line.type == LogType.Report) {
                rootPath.child("log/report/${line.time}-${line.name}.txt").writeString(line.text)
            } else {
                appender(line.type).write("[${line.time}] ${line.text}")
                rotate(line.type, line.time)
            }
            onLogWritten?.invoke(line.type, line.text)
        }.onFailure {
            Log.err("[Essentials] Failed to write ${line.type} log", it)
        }
    }
}

private fun appender(type: LogType): FileAppender =
    logFiles.getOrPut(type) { FileAppender(rootPath.child("log/$type.log").file()) }

private fun rotate(type: LogType, time: String) {
    val current = logFiles[type] ?: return
    if (current.length() <= MAX_LOG_SIZE) return

    current.write("end of file. $time")
    current.close()
    logFiles.remove(type)

    val oldFolder = rootPath.child("log/old/$type")
    oldFolder.mkdirs()
    Files.move(
        Paths.get(rootPath.child("log/$type.log").path()),
        Paths.get(oldFolder.child("$time.log").path()),
        StandardCopyOption.REPLACE_EXISTING
    )

    val rotated = oldFolder.file().listFiles { file -> file.name.endsWith(".log") } ?: return
    if (rotated.size < MAX_LOG_FILE) return

    ZipOutputStream(FileOutputStream(oldFolder.child("$time.zip").file())).use { zip ->
        for (file in rotated) {
            zip.putNextEntry(ZipEntry(file.name))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
    rotated.forEach { it.delete() }
}

class FileAppender(private val file: File) {
    private val raf: RandomAccessFile

    init {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("")
        }
        raf = RandomAccessFile(file, "rw")
        raf.seek(raf.length())
    }

    fun write(text: String) {
        raf.write(("\n$text").toByteArray(StandardCharsets.UTF_8))
    }

    fun length(): Long {
        return file.length()
    }

    fun close() {
        raf.close()
    }
}
