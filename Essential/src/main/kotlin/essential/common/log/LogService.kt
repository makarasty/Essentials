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
private const val ROTATE_RETRY_INTERVAL = 60000L

private data class LogLine(val type: LogType, val text: String, val time: LocalDateTime, val name: String?)

private val queue = ArrayBlockingQueue<LogLine>(QUEUE_CAPACITY)
private val logFiles = HashMap<LogType, FileAppender>()
private val rotateFailed = HashMap<LogType, Long>()
private val running = AtomicBoolean(false)
private val stopped = AtomicBoolean(false)
private val enqueued = AtomicLong()
private val written = AtomicLong()
private val dropped = AtomicLong()
private val reportSeq = AtomicLong()
private val lastDropWarn = AtomicLong()
private val writeLock = Any()
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH_mm_ss")
private val reportTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH_mm_ss_SSS")

@Volatile
private var writer: Thread? = null

internal var onLogWritten: ((LogType, String) -> Unit)? = null

fun initLogFiles() {
    rootPath.child("log/report").mkdirs()
    for (type in LogType.entries) {
        val file = rootPath.child("log/$type.log")
        if (!file.exists()) file.writeString("")
    }
    stopped.set(false)
}

fun isLogEnabled(type: LogType): Boolean {
    val log = Main.conf.feature.log
    return when (type) {
        LogType.Player -> log.player
        LogType.Chat -> log.chat
        LogType.Report -> log.report
        LogType.Block -> log.block
        LogType.Tap -> log.tap
        LogType.Deposit, LogType.WithDraw -> log.item
        LogType.Web -> log.other
    }
}

inline fun writeLog(type: LogType, text: () -> String) {
    if (isLogEnabled(type)) writeLog(type, text())
}

fun writeLog(type: LogType, text: String, vararg name: String) {
    if (!isLogEnabled(type)) return

    val line = LogLine(type, text, LocalDateTime.now(), name.firstOrNull())
    if (stopped.get()) {
        writeBatch(listOf(line))
        return
    }
    startWriter()

    if (queue.offer(line)) {
        enqueued.incrementAndGet()
    } else {
        val total = dropped.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastDropWarn.get()
        if (now - last >= DROP_WARN_INTERVAL && lastDropWarn.compareAndSet(last, now)) {
            Log.warn("[Essentials] Log queue is full, dropped $total lines")
        }
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
    }
}

internal fun stopLogWriter() {
    stopped.set(true)
    if (running.compareAndSet(true, false)) {
        writer?.let {
            it.interrupt()
            it.join()
        }
    }
    writer = null

    synchronized(writeLock) {
        drainQueue(allowRotate = false)
        logFiles.values.forEach { runCatching { it.close() } }
        logFiles.clear()
    }
}

internal fun flushLog() {
    val target = enqueued.get()
    while (written.get() < target) {
        val current = writer
        if (current == null || !current.isAlive) {
            synchronized(writeLock) { drainQueue(allowRotate = true) }
            return
        }
        Thread.sleep(1)
    }
}

private fun drainQueue(allowRotate: Boolean) {
    val rest = ArrayList<LogLine>()
    queue.drainTo(rest)
    if (rest.isNotEmpty()) writeBatch(rest, allowRotate)
}

private fun writeBatch(batch: List<LogLine>, allowRotate: Boolean = true) = synchronized(writeLock) {
    for (line in batch) {
        try {
            if (line.type == LogType.Report) {
                val name = line.name ?: "unknown"
                rootPath.child("log/report/${reportTimeFormat.format(line.time)}-${reportSeq.incrementAndGet()}-$name.txt")
                    .writeString(line.text)
            } else {
                val time = timeFormat.format(line.time)
                appender(line.type).write("[$time] ${line.text}")
                if (allowRotate) rotate(line.type, time)
            }
            onLogWritten?.invoke(line.type, line.text)
        } catch (e: Throwable) {
            Log.err("[Essentials] Failed to write ${line.type} log", e)
        } finally {
            written.incrementAndGet()
        }
    }
}

private fun appender(type: LogType): FileAppender =
    logFiles.getOrPut(type) { FileAppender(rootPath.child("log/$type.log").file()) }

private fun rotate(type: LogType, time: String) {
    val current = logFiles[type] ?: return
    if (current.length() <= MAX_LOG_SIZE) return

    val now = System.currentTimeMillis()
    val failedAt = rotateFailed[type]
    if (failedAt != null && now - failedAt < ROTATE_RETRY_INTERVAL) return

    val source = rootPath.child("log/$type.log")
    val folder = rootPath.child("log/old/$type")
    folder.mkdirs()
    val target = folder.child("$time.log")

    current.close()
    val moved = runCatching {
        Files.move(
            Paths.get(source.path()),
            Paths.get(target.path()),
            StandardCopyOption.REPLACE_EXISTING
        )
    }.isSuccess

    if (!moved) {
        logFiles[type] = FileAppender(source.file())
        if (failedAt == null) {
            Log.warn("[Essentials] Could not rotate the $type log, still writing to it and retrying in a minute")
        }
        rotateFailed[type] = now
        return
    }

    logFiles.remove(type)
    rotateFailed.remove(type)
    target.writeString("\nend of file. $time", true)

    val rotated = folder.file().listFiles { file -> file.name.endsWith(".log") } ?: return
    if (rotated.size < MAX_LOG_FILE) return

    ZipOutputStream(FileOutputStream(folder.child("$time.zip").file())).use { zip ->
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
