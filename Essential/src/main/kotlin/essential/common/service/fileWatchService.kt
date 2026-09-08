package essential.common.service

import arc.Core
import arc.Events
import arc.util.Log
import essential.common.event.CustomEvents
import essential.common.rootPath
import java.nio.file.*

fun fileWatchService() {
    val watchService: WatchService = FileSystems.getDefault().newWatchService()

    try {
        val configPath = Paths.get(rootPath.child("config/").absolutePath())
        Files.createDirectories(configPath)
        configPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

        while (Thread.currentThread().isInterrupted.not()) {
            val watchKey = watchService.take()
            for (event in watchKey.pollEvents()) {
                val kind = event.kind()
                val paths = (event.context() as Path).fileName.toString()
                // Arc runs listeners inline, so firing here would run them on this watcher thread.
                // Config reloads reach Mindustry entity writes (Permission.apply renames players and
                // flips their admin flag) and replace the shared config object, both of which race the
                // server's own per-tick work. Hand the event to the game thread instead.
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
