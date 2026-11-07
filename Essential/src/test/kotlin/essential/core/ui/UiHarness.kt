package essential.core.ui

import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.data.PlayerData
import mindustry.gen.MenuCallPacket
import mindustry.gen.Player
import mindustry.net.NetConnection
import kotlin.test.assertTrue

/** A joined test player whose connection records every packet the server sends it. */
class Sent(val player: Player, val data: PlayerData) {
    val packets = ArrayList<Any>()

    inline fun <reified T> last(): T = packets.filterIsInstance<T>().last()

    val menus get() = packets.filterIsInstance<MenuCallPacket>()
}

/** Shared setup for the ui tests: `Call.menu` and friends only send while `Net.server` is set, which a test JVM never does. */
object UiHarness {
    private var vanillaHandler: arc.util.CommandHandler? = null

    /**
     * Inline so [loadGame] sees the test class as its caller: its class-entry reset is keyed to the caller,
     * and a plain function here would make every ui class look like one class and skip the reset.
     */
    inline fun load() {
        loadGame(true)
        dropUnits()
    }

    /** The reset removes leftover players but not their units; a unit cap already exceeded makes the next spawn call `Net.send`, which has no provider. */
    fun dropUnits() {
        mindustry.gen.Groups.unit.each { it.remove() }
        mindustry.gen.Groups.unit.update()
    }

    private fun netFlag(name: String) = mindustry.net.Net::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** The harness `clientCommand` holds the plugin's commands; [runAsPlayer] reads the engine's, as in production. */
    fun netServer(on: Boolean) {
        listOf("server", "active").forEach { netFlag(it).setBoolean(mindustry.Vars.net, on) }
        if (on) {
            if (vanillaHandler == null) vanillaHandler = mindustry.Vars.netServer.clientCommands
            mindustry.Vars.netServer.clientCommands = clientCommand
        } else {
            vanillaHandler?.let { mindustry.Vars.netServer.clientCommands = it }
        }
    }

    fun joined(group: String): Sent {
        netServer(true)
        val (player, data) = newPlayer()
        val sent = Sent(player, data)
        val old = player.con
        player.con = object : NetConnection(old.address) {
            override fun send(`object`: Any?, reliable: Boolean) {
                if (`object` != null) sent.packets.add(`object`)
            }

            override fun close() {}
        }.also {
            it.uuid = old.uuid
            it.usid = old.usid
        }
        setPermission(player, group, false)
        if (group == "owner") assertTrue(waitUntil(5000) { pumpApp(); essential.common.permission.Permission.check(data, "js") }, "owner group applied")
        return sent
    }
}
