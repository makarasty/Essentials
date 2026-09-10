import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.err
import PluginTest.Companion.log
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import arc.Events
import essential.common.database.data.PlayerData
import essential.common.database.data.checkPlayerBanned
import essential.common.permission.Permission
import essential.common.players
import essential.common.rootPath
import essential.common.timeSource
import essential.core.OwnedMenus
import essential.core.Undo
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.game.EventType.MenuOptionChooseEvent
import mindustry.gen.Player
import mindustry.ui.Menus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class UndoTest {
    companion object {
        private var done = false

        /**
         * The id the block opened its owned menu under.
         *
         * This used to count `Menus.menuListeners`, taking the first index the block registered and
         * requiring the count to have moved by exactly one. That question can no longer be asked of
         * the engine's list: `OwnedMenus` registers each slot's listener once and hands the id out
         * again when nothing can still answer on it, so a menu opened on a recycled slot does not
         * move that list at all. `OwnedMenus.allocationCount` counts the same event one level up - a
         * menu handed to a player - and the claim is unchanged, including the "exactly one": if a
         * second owned menu is opened in the window, "the id this block took" is ambiguous, and a
         * click on the wrong one reaches a different listener silently because `menuChoose` only
         * range-checks the id. Two is still a named failure, not something addressed by accident.
         */
        private fun menuOpenedBy(what: String, block: () -> Unit): Int {
            val before = OwnedMenus.allocationCount
            block()
            val ids = OwnedMenus.idsAllocatedAfter(before)
            assertEquals(
                1, ids.size,
                "$what should have opened exactly one owned menu, otherwise this test proves nothing"
            )
            return ids.single()
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

    /**
     * By uuid, not by name. `/info` resolves an online target synchronously and opens the menu on
     * it before returning (Commands.kt, `PlayerLookup.findOnline` -> `open(current)`); every other
     * outcome falls to `scope.launch { ... Core.app.post { open(other) } }`, and that post is only
     * drained by `pumpApp`, which nothing between here and the click below calls. The menu is
     * registered either way, so its id is right either way - but its captured `targetData` is
     * still null, and the kick branch is a no-op on a null target. Two players sharing a plain
     * name is enough to take that path, and the harness names players from a faker surname plus a
     * millisecond. A uuid matches exactly, ahead of any name matching, and cannot be ambiguous.
     * `/info <name>` itself is covered by ClientCommandTest.
     */
    private fun openInfo(admin: Player, target: Player): Int =
        menuOpenedBy("/info") { clientCommand.handleMessage("/info ${target.uuid()}", admin) }

    /** Clicking an /info menu option that opens the next menu; returns that menu's own id. */
    private fun choose(admin: Player, menu: Int, option: Int): Int =
        menuOpenedBy("option $option") { Menus.menuChoose(admin, menu, option) }

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
        val banMenu = choose(admin, infoMenu, 1)
        val confirmMenu = choose(admin, banMenu, 6)
        Menus.menuChoose(admin, confirmMenu, 0)

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
    fun undo_setPermLeavesNoUserEntry() {
        val admin = admin("owner")
        val target = newPlayer().first
        val uuid = target.uuid()

        assertFalse(Permission.hasUserEntry(uuid), "a fresh player should have no yaml entry")

        clientCommand.handleMessage("/setperm ${target.name} admin", admin)
        assertFalse(Permission.hasUserEntry(uuid), "setperm should not write an entry that masks the shared row")
        assertEquals("admin", Permission.groupOf(uuid, data(target).permission), "the group should still apply")

        clientCommand.handleMessage("/undo", admin)
        assertFalse(Permission.hasUserEntry(uuid), "undo should leave no entry behind either")
        assertFalse(
            rootPath.child("permission_user.yaml").readString().contains(uuid),
            "no entry for this player should ever reach the file"
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

    /**
     * Undo.take removes the entry before revert runs, so a refused revert cannot be retried and the
     * admin has to be told it did not apply. Only the setperm revert can fail that way, and only
     * through permission_user.yaml - so the report has to branch on the entry's action, not on the
     * global alone, which is persistent state that would otherwise report a permission error after a
     * mute undo that worked.
     */
    @Test
    fun undo_setpermReportsThePermissionFileProblemAndOtherActionsDoNot() {
        val admin = admin()
        val target = newPlayer().first
        val adminData = data(admin)

        val userFile = rootPath.child("permission_user.yaml")
        val saved = if (userFile.exists()) userFile.readString() else null
        try {
            Undo.record(adminData, "mute", target.uuid(), target.name) { }
            Undo.record(adminData, "setperm", target.uuid(), target.name) { }

            userFile.writeString("this: [is not: valid yaml", false)
            Permission.load()
            val problem = Permission.userFileProblem()
            assertNotNull(problem, "the corrupted permission_user.yaml should be reported as a problem")

            clientCommand.handleMessage("/undo", admin)
            assertEquals(
                err("permission.user.file.invalid", problem),
                adminData.lastReceivedMessage,
                "a setperm undo must report the permission file problem instead of claiming it was done"
            )

            clientCommand.handleMessage("/undo", admin)
            assertTrue(
                adminData.lastReceivedMessage.contains(log("command.undo.action.mute", target.name)),
                "a mute undo must still report success while the permission file is broken"
            )
        } finally {
            if (saved != null) userFile.writeString(saved, false) else userFile.delete()
            Permission.load()
        }
    }

}
