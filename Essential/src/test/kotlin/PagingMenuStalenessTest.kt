import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import arc.struct.Seq
import mindustry.Vars
import mindustry.gen.Player
import mindustry.ui.Menus
import kotlin.math.ceil
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * task-128: /maps and /players used to page their own menu through the same PlayerData.status["page"]
 * key. registerOwnedMenu hands out a fresh, permanently-registered id per invocation (see the notes -
 * the one-shared-id fix was reverted because it created a worse hazard), so a menu opened by one
 * command and left on screen is still live when a second command opens and pages its own, differently
 * sized menu. Paging the second menu wrote a page number sized for its own prebuilt array into the
 * shared key; firing the first (still-registered) menu's listener then indexed its own, smaller
 * prebuilt array with that stale number.
 */
class PagingMenuStalenessTest {
    companion object {
        private var done = false

        private fun lastMenuId(): Int {
            val field = Menus::class.java.getDeclaredField("menuListeners")
            field.isAccessible = true
            return (field.get(null) as Seq<*>).size - 1
        }

        /**
         * The id the block registered, not whatever the process registered last. `menuListeners`
         * is process-wide, so "the last id" is a question about the whole JVM; the first newly
         * registered index is this block's own. It matters here because the two menus this test
         * addresses are opened either side of dozens of player joins, each of which pumps the
         * app queue.
         */
        private fun menuRegisteredBy(what: String, block: () -> Unit): Int {
            val before = lastMenuId()
            block()
            assertTrue(lastMenuId() > before, "$what should have registered its own menu, otherwise this test proves nothing")
            return before + 1
        }
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    @Test
    fun aStaleMenusListenerDoesNotIndexItsPrebuiltArrayWithAnotherMenusPageNumber() {
        val (player, _) = newPlayer()
        val extras = mutableListOf<Player>()
        try {
            // /maps' own page count, from the same formula the command uses - so this does not assume
            // anything about how many real maps the harness has loaded.
            val mapsPages = run {
                val count = Vars.maps.all().size
                val buffer = ceil(count / 6.0)
                if (buffer > 1.0) (buffer - 1).toInt() else 0
            }

            // Opened first and never clicked: it stays registered (registerOwnedMenu leaks by design
            // again, per that revert) and stays reachable once whatever opens next is dismissed.
            val mapsMenu = menuRegisteredBy("/maps") { clientCommand.handleMessage("/maps", player) }

            // Enough players that /players has strictly more pages than /maps, so paging it can drive
            // the shared key past the end of /maps' own, smaller prebuilt array.
            repeat((mapsPages + 2) * 6) {
                extras.add(newPlayer().first)
            }

            val playersMenu = menuRegisteredBy("/players") { clientCommand.handleMessage("/players", player) }
            assertTrue(playersMenu > mapsMenu, "/players should have registered its own, later menu")

            // Page /players forward past mapsPages - each click used to read and rewrite the shared
            // status["page"] key through /players' own listener.
            repeat(mapsPages + 1) {
                Menus.menuChoose(player, playersMenu, 2) // "->"
            }

            // The stale /maps listener is still registered under mapsMenu. Firing it must not reach for
            // a page number another menu advanced - before the fix this indexed /maps' own prebuilt
            // array (size mapsPages + 1) with a stale, larger number and threw IndexOutOfBoundsException.
            Menus.menuChoose(player, mapsMenu, 1) // re-show the current page
        } finally {
            leavePlayer(player)
            extras.forEach { leavePlayer(it) }
        }
    }
}
