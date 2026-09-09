import PluginTest.Companion.loadGame
import arc.util.Log
import essential.common.database.data.getPlayerData
import essential.common.database.data.getPluginData
import essential.common.database.data.update
import essential.common.database.databaseInit
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The old half of the mixed-version rollout check. Compiled and run inside a worktree at 76444608 -
 * the commit before the blocker run - against the same database as the current-build half, so that a
 * staggered rollout is exercised rather than argued about.
 *
 * This file is a test *resource* in the current tree and is copied into the old worktree by
 * run-mixed-version.sh. It only compiles against the old API: the old PlayerData carries no
 * dbSnapshot, and the old PluginData has no update() member, only the generated extension.
 *
 * args: r2dbcUrl user password uuid rendezvousDir
 */
fun main(args: Array<String>) {
    val (url, user, pass, uuid, rendezvous) = args
    val dir = File(rendezvous).also { it.mkdirs() }

    loadGame(deleteConfig = false)
    File("config/mods/Essentials/data").mkdirs()
    // loadGame installs a handler that throws on Log.err; the whole point here is to read what a
    // staggered rollout says, so print it instead.
    Log.logger = Log.LogHandler { _, text -> println("[old] $text") }

    // An exception out of main would otherwise leave the headless application's non-daemon threads
    // running, so the script waits forever on a process that has already failed. Halt instead.
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        e.printStackTrace()
        Runtime.getRuntime().halt(1)
    }

    runBlocking {
        databaseInit(url, user, pass)
        // The two instances boot one after the other on purpose. Booting both at once against an
        // empty database is its own defect - SchemaUtils.create races itself and one instance dies
        // with "Duplicate key name" - and mixing the two would leave this unable to say which it saw.
        File(dir, "old-booted").writeText("ok")

        await(File(dir, "new-ready"))
        val player = checkNotNull(getPlayerData(uuid)) { "old instance found no row for $uuid" }
        val plugin = checkNotNull(getPluginData()) { "old instance found no plugin_data row" }
        println("[old] read player: exp=${player.exp} banned=${player.isBanned} perm=${player.permission} muted=${player.chatMuted}")
        println("[old] read blob: warpBlocks=${plugin.data.warpBlock.size} tempBans=${plugin.data.tempBans.keys}")
        File(dir, "old-read").writeText("ok")

        await(File(dir, "new-wrote"))
        // The old instance saves for a reason of its own, from the copy it took above.
        player.blockPlaceCount = 42
        println("[old] saved player: ${player.update()}")
        plugin.data.blacklistedNames.add("old-instance-was-here")
        println("[old] saved blob: ${plugin.update()}")
        File(dir, "old-saved").writeText("ok")
    }

    // The headless application keeps non-daemon threads; nothing here needs an orderly shutdown, and
    // PluginTest.stopPlugin() would send SHUTDOWN to the shared server.
    Runtime.getRuntime().halt(0)
}

private fun await(file: File, timeoutMs: Long = 300_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!file.exists()) {
        check(System.currentTimeMillis() < deadline) { "old instance timed out waiting for ${file.name}" }
        Thread.sleep(200)
    }
}
