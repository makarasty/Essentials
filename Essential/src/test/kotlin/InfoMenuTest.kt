import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import arc.struct.Seq
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

        private fun lastMenuId(): Int {
            val field = Menus::class.java.getDeclaredField("menuListeners")
            field.isAccessible = true
            return (field.get(null) as Seq<*>).size - 1
        }
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        // Undo registers its own menu lazily, on the first MenuOptionChooseEvent. Force it here so that
        // counting registered menus below measures only what the /info menu did.
        Undo.menuId
    }

    private fun admin(): Player = newPlayer().first.also { setPermission(it, "admin", true) }

    private fun openInfo(admin: Player, target: Player): Int {
        val before = lastMenuId()
        clientCommand.handleMessage("/info ${target.name}", admin)
        val menu = lastMenuId()
        assertTrue(menu > before, "/info should have registered its menu, otherwise this test proves nothing")
        return menu
    }

    @Test
    fun infoMenu_strangerCannotOpenTheBanMenu() {
        val admin = admin()
        val stranger = newPlayer().first
        val target = newPlayer().first

        val infoMenu = openInfo(admin, target)
        val before = lastMenuId()
        Menus.menuChoose(stranger, infoMenu, 1)
        assertEquals(before, lastMenuId(), "a stranger's click must not open the admin's ban menu")

        Menus.menuChoose(admin, infoMenu, 1)
        assertTrue(lastMenuId() > before, "the admin who opened the menu must still reach the ban menu")
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
        Menus.menuChoose(admin, infoMenu, 1)
        Menus.menuChoose(admin, lastMenuId(), 6)
        val confirmMenu = lastMenuId()

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
