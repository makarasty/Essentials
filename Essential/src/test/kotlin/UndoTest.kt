import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.log
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import arc.Events
import arc.struct.Seq
import essential.common.database.data.PlayerData
import essential.common.database.data.checkPlayerBanned
import essential.common.permission.Permission
import essential.common.players
import essential.common.timeSource
import essential.core.Undo
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.game.EventType.MenuOptionChooseEvent
import mindustry.gen.Player
import mindustry.ui.Menus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class UndoTest {
    companion object {
        private var done = false

        private fun lastMenuId(): Int {
            val field = Menus::class.java.getDeclaredField("menuListeners")
            field.isAccessible = true
            return (field.get(null) as Seq<*>).size - 1
        }

        private fun data(player: Player): PlayerData = players.first { it.uuid == player.uuid() }
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    private fun admin(group: String = "admin"): Player {
        val player = newPlayer().first
        setPermission(player, group, true)
        return player
    }

    private fun openInfo(admin: Player, target: Player): Int {
        clientCommand.handleMessage("/info ${target.name}", admin)
        return lastMenuId()
    }

    private fun clickUndoMenu(admin: Player, option: Int) {
        Events.fire(MenuOptionChooseEvent(admin, Undo.menuId, option))
    }

    @Test
    fun undo_banRestoresJoin() {
        val admin = admin()
        val target = newPlayer().first
        val uuid = target.uuid()
        val ip = target.con.address
        val name = target.name

        val infoMenu = openInfo(admin, target)
        Menus.menuChoose(admin, infoMenu, 1)
        Menus.menuChoose(admin, lastMenuId(), 6)
        Menus.menuChoose(admin, lastMenuId(), 0)

        assertTrue(Vars.netServer.admins.isIDBanned(uuid), "target should be banned")
        assertTrue(
            waitUntil(10000) { runBlocking { checkPlayerBanned(uuid, ip, name) } },
            "ban should be stored by the plugin"
        )

        clickUndoMenu(admin, 0)

        assertFalse(Vars.netServer.admins.isIDBanned(uuid), "ban list should be cleared")
        assertFalse(Vars.netServer.admins.isIPBanned(ip), "ip ban should be cleared")
        assertTrue(
            waitUntil(10000) { runBlocking { !checkPlayerBanned(uuid, ip, name) } },
            "plugin ban state should be cleared"
        )
        assertTrue(Undo.stack(admin.uuid()).isEmpty(), "undone entry should leave the stack")
    }

    @Test
    fun undo_kickAlternativeBans() {
        val admin = admin()
        val target = newPlayer().first
        val uuid = target.uuid()
        val ip = target.con.address

        val infoMenu = openInfo(admin, target)
        Menus.menuChoose(admin, infoMenu, 2)

        assertTrue(Vars.netServer.admins.kickedIPs.containsKey(ip), "kick should block the address")

        clickUndoMenu(admin, 2)

        assertTrue(Vars.netServer.admins.isIDBanned(uuid), "alternative button should ban the target")
    }

    @Test
    fun undo_kickLiftsBlock() {
        val admin = admin()
        val target = newPlayer().first
        val uuid = target.uuid()
        val ip = target.con.address

        val infoMenu = openInfo(admin, target)
        Menus.menuChoose(admin, infoMenu, 2)

        assertTrue(Vars.netServer.admins.getKickTime(uuid, ip) > 0, "kick should block a rejoin")

        clickUndoMenu(admin, 0)

        assertEquals(0L, Vars.netServer.admins.getKickTime(uuid, ip), "undo should let the player rejoin")
    }

    @Test
    fun undo_muteThenUnmute() {
        val admin = admin()
        val target = newPlayer().first

        clientCommand.handleMessage("/mute ${target.name}", admin)
        assertTrue(
            waitUntil(10000) { data(target).chatMuted && Undo.stack(admin.uuid()).isNotEmpty() },
            "target should be muted"
        )

        clientCommand.handleMessage("/undo", admin)
        assertTrue(waitUntil(10000) { !data(target).chatMuted }, "undo should unmute the target")
    }

    @Test
    fun undo_setPermRestoresGroup() {
        val admin = admin("owner")
        val target = newPlayer().first
        val uuid = target.uuid()
        val before = Permission.groupOf(uuid, data(target).permission)

        clientCommand.handleMessage("/setperm ${target.name} admin", admin)
        assertEquals("admin", Permission.groupOf(uuid, data(target).permission))

        clientCommand.handleMessage("/undo", admin)
        assertEquals(before, Permission.groupOf(uuid, data(target).permission))
    }

    @Test
    fun undo_emptyStackAnswers() {
        val admin = admin()

        clientCommand.handleMessage("/undo", admin)
        assertEquals(log("command.undo.empty"), data(admin).lastReceivedMessage)

        clientCommand.handleMessage("/undo list", admin)
        assertEquals(log("command.undo.empty"), data(admin).lastReceivedMessage)
    }

    @Test
    fun undo_entriesExpire() {
        val admin = admin()
        val target = newPlayer().first

        clientCommand.handleMessage("/mute ${target.name}", admin)
        assertTrue(waitUntil(10000) { Undo.stack(admin.uuid()).isNotEmpty() }, "mute should be recorded")

        clientCommand.handleMessage("/undo list", admin)
        assertTrue(data(admin).lastReceivedMessage.contains("1."), "list should number the entries")

        Undo.stack(admin.uuid()).forEach { it.expiresAt = timeSource.markNow().minus(1.minutes) }
        assertTrue(Undo.stack(admin.uuid()).isEmpty(), "expired entries should be dropped")

        clientCommand.handleMessage("/undo", admin)
        assertEquals(log("command.undo.empty"), data(admin).lastReceivedMessage)
    }
}
