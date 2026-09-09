import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import essential.core.Commands
import essential.core.Undo
import mindustry.Vars
import mindustry.gen.Player
import mindustry.ui.Menus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The /info admin menu acts on a target the admin picked. Menu ids are global and menuChoose is a remote
 * any client may call with any id, so these tests hold the line that only the admin the menu was opened
 * for can drive it. Each one also checks that the admin still can, so that a menu which failed to open
 * cannot pass as a menu that refused a stranger.
 *
 * What "the menu opened" is measured by: Commands registers exactly one menu id for every owned menu it
 * ever shows, and routes an incoming choice by the responder's uuid through `ownedMenuListeners`. So the
 * observable that a menu was opened - or advanced a step - is that the owner's entry in that map was
 * replaced, not that the engine's global menu list grew. It grew once, on the first owned menu of the
 * process, and never again; counting it is what this file used to do and it stopped meaning anything
 * when the per-menu registration leak was fixed.
 */
class InfoMenuTest {
    companion object {
        private var done = false

        private fun ownedMenuStatic(name: String): Any? =
            Commands::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null)

        /** The listener currently registered for [uuid], or null when this player owns no menu. */
        private fun ownedListener(uuid: String): Any? =
            (ownedMenuStatic("ownedMenuListeners") as Map<*, *>)[uuid]

        /** The one menu id Commands registers for every owned menu. */
        private fun ownedMenuId(): Int = (ownedMenuStatic("ownedMenuId\$delegate") as Lazy<*>).value as Int
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        // Undo registers its own menu lazily, on the first MenuOptionChooseEvent. Force it here so that
        // nothing below is the first thing in the process to touch the engine's menu registry.
        Undo.menuId
    }

    private fun admin(): Player = newPlayer().first.also { setPermission(it, "admin", true) }

    private fun openInfo(admin: Player, target: Player): Int {
        val before = ownedListener(admin.uuid())
        clientCommand.handleMessage("/info ${target.name}", admin)
        val after = ownedListener(admin.uuid())
        assertNotNull(after, "/info should have registered its menu, otherwise this test proves nothing")
        assertNotSame(before, after, "/info should have registered its menu, otherwise this test proves nothing")
        return ownedMenuId()
    }

    @Test
    fun infoMenu_strangerCannotOpenTheBanMenu() {
        val admin = admin()
        val stranger = newPlayer().first
        val target = newPlayer().first

        val infoMenu = openInfo(admin, target)
        val before = ownedListener(admin.uuid())
        Menus.menuChoose(stranger, infoMenu, 1)
        assertSame(before, ownedListener(admin.uuid()), "a stranger's click must not open the admin's ban menu")

        Menus.menuChoose(admin, infoMenu, 1)
        assertNotSame(
            before, ownedListener(admin.uuid()),
            "the admin who opened the menu must still reach the ban menu"
        )
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
        Menus.menuChoose(admin, infoMenu, 6)

        Menus.menuChoose(stranger, infoMenu, 0)
        assertFalse(Vars.netServer.admins.isIDBanned(uuid), "a stranger must not answer the admin's confirmation")

        Menus.menuChoose(admin, infoMenu, 0)
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
