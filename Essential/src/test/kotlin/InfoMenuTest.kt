import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.ownedMenuOpenedBy
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import essential.core.OwnedMenus
import essential.core.Undo
import mindustry.Vars
import mindustry.gen.Player
import mindustry.ui.Menus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The /info admin menu acts on a target the admin picked. Menu ids are global and menuChoose is a remote
 * any client may call with any id, so these tests hold the line that only the admin the menu was opened
 * for can drive it. Each one also checks that the admin still can, so that a menu which failed to open
 * cannot pass as a menu that refused a stranger.
 */
class InfoMenuTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    private fun admin(): Player = newPlayer().first.also { setPermission(it, "admin", true) }

    /**
     * By uuid, not by name: `/info` only resolves an online target and opens the menu on it
     * synchronously when the lookup is unambiguous, and two players sharing a plain name is
     * enough to send it down `scope.launch { ... Core.app.post { open(other) } }` instead. The
     * menu is registered either way, so the id below is right either way - but nothing here
     * pumps the app queue, so the menu's captured target would still be null when it is clicked.
     * A uuid matches exactly and cannot be ambiguous. `/info <name>` is ClientCommandTest's.
     */
    private fun openInfo(admin: Player, target: Player): Int =
        ownedMenuOpenedBy("/info") { clientCommand.handleMessage("/info ${target.uuid()}", admin) }

    /** Clicking an option that opens the next menu; returns that menu's own id. */
    private fun choose(player: Player, menu: Int, option: Int, what: String = "option $option"): Int =
        ownedMenuOpenedBy(what) { Menus.menuChoose(player, menu, option) }

    @Test
    fun infoMenu_strangerCannotOpenTheBanMenu() {
        val admin = admin()
        val stranger = newPlayer().first
        val target = newPlayer().first

        val infoMenu = openInfo(admin, target)
        // Nothing was opened at all. This asked the engine's list the same question until owned menu
        // ids became reusable - and a reused slot registers nothing with the engine, so a stranger's
        // click that *did* open the ban menu could have left that list untouched and passed. Asking
        // OwnedMenus makes the claim true again rather than merely still green.
        val before = OwnedMenus.allocationCount
        Menus.menuChoose(stranger, infoMenu, 1)
        assertEquals(
            before, OwnedMenus.allocationCount,
            "a stranger's click must not open the admin's ban menu"
        )

        choose(admin, infoMenu, 1, "the admin who opened the menu must still reach the ban menu, so option 1")
    }

    @Test
    fun infoMenu_strangerCannotConfirmABan() {
        val admin = admin()
        val stranger = newPlayer().first
        val target = newPlayer().first
        val uuid = target.uuid()
        val ip = target.con.address
        val ipBannedBefore = Vars.netServer.admins.bannedIPs.contains(ip)

        val infoMenu = openInfo(admin, target)
        val banMenu = choose(admin, infoMenu, 1)
        val confirmMenu = choose(admin, banMenu, 6)

        Menus.menuChoose(stranger, confirmMenu, 0)
        assertFalse(Vars.netServer.admins.isIDBanned(uuid), "a stranger must not answer the admin's confirmation")

        Menus.menuChoose(admin, confirmMenu, 0)
        assertTrue(Vars.netServer.admins.isIDBanned(uuid), "the admin who opened the menu must still be able to ban")

        Vars.netServer.admins.unbanPlayerID(uuid)
        if (!ipBannedBefore) Vars.netServer.admins.unbanPlayerIP(ip)
    }

    @Test
    fun infoMenu_strangerCannotKick() {
        val admin = admin()
        val stranger = newPlayer().first
        val target = newPlayer().first

        val infoMenu = openInfo(admin, target)
        Menus.menuChoose(stranger, infoMenu, 2)
        assertTrue(
            Undo.stack(admin.uuid()).isEmpty(),
            "a stranger's click must not kick the target under the admin's name"
        )

        Menus.menuChoose(admin, infoMenu, 2)
        assertTrue(
            Undo.stack(admin.uuid()).isNotEmpty(),
            "the admin who opened the menu must still be able to kick"
        )
    }
}
