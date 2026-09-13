package essential.common.service

import arc.Core
import arc.Events
import arc.util.Log
import essential.common.event.CustomEvents
import essential.common.rootPath
import java.nio.file.*

/**
 * Blocks until [file] has stopped changing, or until the wait has gone on long enough to be somebody
 * else's problem. Size and modification time together: a same-size overwrite still moves the clock.
 */
private fun awaitSettled(file: Path) {
    var previous: Pair<Long, Long>? = null
    repeat(20) {
        val current = try {
            Files.size(file) to Files.getLastModifiedTime(file).toMillis()
        } catch (_: java.io.IOException) {
            // Gone, or not readable yet. Nothing to wait for; the reader reports its own failure.
            return
        }
        if (current == previous) return
        previous = current
        Thread.sleep(100)
    }
}

fun fileWatchService() {
    val watchService: WatchService = FileSystems.getDefault().newWatchService()

    try {
        val configPath = Paths.get(rootPath.child("config/").absolutePath())
        Files.createDirectories(configPath)
        configPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

        while (Thread.currentThread().isInterrupted.not()) {
            val watchKey = watchService.take()
            // distinctBy: an editor saving one file emits several ENTRY_MODIFY events, and each one
            // used to cost a full reload of the same file.
            for (event in watchKey.pollEvents().distinctBy { (it.context() as Path).fileName.toString() }) {
                val kind = event.kind()
                val paths = (event.context() as Path).fileName.toString()
                // Wait for the write to finish before anyone reads the file. An editor that saves in
                // pieces leaves, in between, a file that still parses but is missing everything it has
                // not written yet - and Config.load answers a missing key by filling it from a default
                // and writing the whole file back, over the half the operator was still saving.
                awaitSettled(configPath.resolve(paths))
                // Arc runs listeners inline, so firing here would run them on this watcher thread.
                // Config reloads reach Mindustry entity writes (Permission.apply renames players and
                // flips their admin flag) and replace the shared config object, both of which race the
                // server's own per-tick work.
                Core.app.post {
                    try {
                        Events.fire(CustomEvents.ConfigFileModified(kind, paths))
                    } catch (e: Throwable) {
                        // A listener that threw used to cost this watcher thread. On the game thread it
                        // would cost the server's main loop, so a failed reload stays a failed reload.
                        Log.err("Failed to apply a configuration file change.", e)
                    }
                }
            }

            if (!watchKey.reset()) {
                break
            }
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    } catch (_: NoSuchFileException) {
        // The plugin can be disposed while the watcher is starting (notably in
        // headless smoke tests). There is no directory left to watch.
    } catch (_: ClosedWatchServiceException) {
        // Normal shutdown closes the service while the watcher is blocked.
    } finally {
        watchService.close()
    }
}
