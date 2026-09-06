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
import essential.common.rootPath
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
        assertTrue(Vars.netServer.admins.bannedIPs.contains(ip), "the ban should place an ip ban")
        assertTrue(
            waitUntil(10000) { runBlocking { checkPlayerBanned(uuid, ip, name) } },
            "ban should be stored by the plugin"
        )

        clickUndoMenu(admin, 0)

        assertFalse(Vars.netServer.admins.isIDBanned(uuid), "ban list should be cleared")
        assertFalse(Vars.netServer.admins.bannedIPs.contains(ip), "the ip ban placed by the ban should be lifted")
        assertTrue(
            waitUntil(10000) { runBlocking { !checkPlayerBanned(uuid, ip, name) } },
            "plugin ban state should be cleared"
        )
        assertTrue(Undo.stack(admin.uuid()).isEmpty(), "undone entry should leave the stack")
    }

    @Test
    fun undo_banKeepsAnEarlierIpBan() {
        val target = newPlayer().first
        val uuid = target.uuid()
        val ip = target.con.address

        Vars.netServer.admins.banPlayerIP(ip)

        val ipBanned = Undo.ban(uuid)
        assertFalse(ipBanned, "the ban must not claim an ip ban that was already in place")
        assertTrue(Vars.netServer.admins.isIDBanned(uuid), "target should be banned")

        Undo.unban(uuid, ipBanned)

        assertTrue(
            Vars.netServer.admins.bannedIPs.contains(ip),
            "an ip ban the undone action did not place must survive"
        )

        Vars.netServer.admins.unbanPlayerIP(ip)
        Vars.netServer.admins.unbanPlayerID(uuid)
    }

    @Test
    fun undo_pendingClearedByCommand() {
        val admin = admin()
        val target = newPlayer().first
        val uuid = target.uuid()
        val ip = target.con.address

        val infoMenu = openInfo(admin, target)
        Menus.menuChoose(admin, infoMenu, 2)
        assertTrue(waitUntil(10000) { Undo.stack(admin.uuid()).isNotEmpty() }, "kick should be recorded")

        clientCommand.handleMessage("/undo", admin)
        assertEquals(0L, Vars.netServer.admins.getKickTime(uuid, ip), "undo should let the player rejoin")

        clickUndoMenu(admin, 2)

        assertFalse(
            Vars.netServer.admins.isIDBanned(uuid),
            "the leftover menu must not ban after the kick was already undone"
        )
    }

    @Test
    fun undo_entriesKeepStableIds() {
        val admin = admin()
        val first = newPlayer().first
        val second = newPlayer().first

        clientCommand.handleMessage("/mute ${first.name}", admin)
        assertTrue(waitUntil(10000) { Undo.stack(admin.uuid()).size == 1 }, "first mute should be recorded")
        val firstId = Undo.stack(admin.uuid()).first().id

        clientCommand.handleMessage("/mute ${second.name}", admin)
        assertTrue(waitUntil(10000) { Undo.stack(admin.uuid()).size == 2 }, "second mute should be recorded")
        assertEquals(firstId, Undo.stack(admin.uuid()).last().id, "an id must not shift when the stack grows")

        clientCommand.handleMessage("/undo $firstId", admin)
        assertTrue(waitUntil(10000) { !data(first).chatMuted }, "/undo <id> should revert the entry with that id")
        assertTrue(data(second).chatMuted, "the newer entry should be untouched")
    }

    @Test
    fun undo_setPermRemovesTheEntryItCreated() {
        val admin = admin("owner")
        val target = newPlayer().first
        val uuid = target.uuid()

        assertFalse(Permission.hasUserEntry(uuid), "a fresh player should have no yaml entry")

        clientCommand.handleMessage("/setperm ${target.name} admin", admin)
        assertTrue(Permission.hasUserEntry(uuid), "setperm should create a yaml entry")

        clientCommand.handleMessage("/undo", admin)
        assertFalse(Permission.hasUserEntry(uuid), "undo should remove the entry setperm created")
        assertFalse(
            rootPath.child("permission_user.yaml").readString().contains(uuid),
            "the created entry should be gone from the file"
        )
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

        val id = Undo.stack(admin.uuid()).first().id
        clientCommand.handleMessage("/undo list", admin)
        assertTrue(data(admin).lastReceivedMessage.contains("$id."), "list should show the entry id")

        Undo.stack(admin.uuid()).forEach { it.expiresAt = timeSource.markNow().minus(1.minutes) }
        assertTrue(Undo.stack(admin.uuid()).isEmpty(), "expired entries should be dropped")

        clientCommand.handleMessage("/undo", admin)
        assertEquals(log("command.undo.empty"), data(admin).lastReceivedMessage)
    }
}
