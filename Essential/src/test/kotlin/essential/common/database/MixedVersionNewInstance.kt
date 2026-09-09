package essential.common.database

import PluginTest.Companion.loadGame
import arc.util.Log
import essential.common.database.data.createPlayerData
import essential.common.database.data.createPluginData
import essential.common.database.data.getPlayerData
import essential.common.database.data.getPluginData
import essential.common.database.data.plugin.WarpBlock
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The current-build half of the mixed-version rollout check. It is a main rather than a test because
 * its other half is a second process running a build of this plugin from 76444608, and the two have to
 * be alive at the same time; run-mixed-version.sh under src/test/resources/mixed-version starts both.
 *
 * It reports rather than asserts, and exits non-zero when the old instance reverted anything, so the
 * script's exit status is the result. Everything both processes log goes to their own stdout, which is
 * what answers the third question the run asks: whether a botched rollout says anything at all.
 *
 * args: r2dbcUrl user password uuid rendezvousDir
 */
/** What the old instance adds to the blob on its way past; the new instance clears it first. */
private const val OLD_INSTANCE_MARK = "old-instance-was-here"

fun main(args: Array<String>) {
    val (url, user, pass, uuid, rendezvous) = args
    val dir = File(rendezvous).also { it.mkdirs() }
    val victim = "mixed-version-temp-ban"
    val map = "mixed-version-hub"

    loadGame(deleteConfig = false)
    File("config/mods/Essentials/data").mkdirs()
    Log.logger = Log.LogHandler { _, text -> println("[new] $text") }

    // An exception out of main would otherwise leave the headless application's non-daemon threads
    // running, so the script waits forever on a process that has already failed. Halt instead.
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        e.printStackTrace()
        Runtime.getRuntime().halt(1)
    }

    val failures = mutableListOf<String>()

    runBlocking {
        // Wait for the old instance to finish its own boot first: two instances running SchemaUtils
        // against one empty database at the same time is a separate defect, and this scenario is about
        // what the old jar does once both are up.
        await(File(dir, "old-booted"))
        databaseInit(url, user, pass)

        // Everything this run asserts on, cleared first. The database is not dropped between runs and
        // the uuid is stable, so without this the two "never landed, so nothing means anything" controls
        // below would be satisfied by the previous run's leftovers - and those controls are the only
        // thing separating "the fix worked" from "the old instance never started".
        val plugin = getPluginData() ?: createPluginData()
        plugin.data.warpBlock.removeAll { it.mapName == map }
        plugin.data.tempBans.remove(victim)
        plugin.data.blacklistedNames.remove(OLD_INSTANCE_MARK)
        plugin.update()

        val player = getPlayerData(uuid) ?: createPlayerData(uuid, uuid, uuid, uuid)
        player.blockPlaceCount = 0
        player.isBanned = false
        player.chatMuted = false
        player.permission = "default"
        player.exp = 0
        player.update()
        println("[new] row ready for $uuid")
        File(dir, "new-ready").writeText("ok")

        // Wait until the old instance has taken its own copy of both rows: that copy is what a server
        // running the old jar holds from the moment the player joined, or from its own boot.
        await(File(dir, "old-read"))

        player.isBanned = true
        player.chatMuted = true
        player.permission = "admin"
        player.exp = 777
        println("[new] wrote player columns: ${player.update()}")

        plugin.data.warpBlock.add(WarpBlock(map, 1, 2, "router", 1, "127.0.0.1", 6567, "added by the new instance"))
        plugin.data.tempBans[victim] = "2099-01-01T00:00"
        println("[new] wrote a warp and a temp ban: ${plugin.update()}")
        File(dir, "new-wrote").writeText("ok")

        // The old instance now saves for a reason of its own.
        await(File(dir, "old-saved"))

        val after = checkNotNull(getPlayerData(uuid)) { "the player row disappeared" }
        val blob = checkNotNull(getPluginData()) { "the plugin_data row disappeared" }

        if (after.blockPlaceCount != 42) failures += "the old instance's own write never landed, so nothing below means anything"
        if (!after.isBanned) failures += "the old instance reverted the ban"
        if (!after.chatMuted) failures += "the old instance reverted the mute"
        if (after.permission != "admin") failures += "the old instance reverted the permission group to ${after.permission}"
        if (after.exp != 777) failures += "the old instance reverted exp to ${after.exp}"
        if (blob.data.warpBlock.none { it.mapName == map }) failures += "the old instance erased the warp the new one added"
        if (!blob.data.tempBans.containsKey(victim)) failures += "the old instance erased the temp ban the new one issued"
        if (!blob.data.blacklistedNames.contains(OLD_INSTANCE_MARK)) {
            failures += "the old instance's own blob write never landed, so nothing above means anything"
        }

        println("[new] player row after the old instance saved: $after")
        println("[new] blob after the old instance saved: warpBlocks=${blob.data.warpBlock.size} tempBans=${blob.data.tempBans.keys}")
    }

    if (failures.isEmpty()) {
        println("[new] RESULT: the old instance reverted nothing")
    } else {
        failures.forEach { println("[new] RESULT: $it") }
    }
    Runtime.getRuntime().halt(if (failures.isEmpty()) 0 else 1)
}

private fun await(file: File, timeoutMs: Long = 300_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!file.exists()) {
        check(System.currentTimeMillis() < deadline) { "the new instance timed out waiting for ${file.name}" }
        Thread.sleep(200)
    }
}
