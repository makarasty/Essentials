import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import mindustry.ui.Menus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-127/task-138: registerOwnedMenu used to call Menus.registerMenu - which appends to a process-wide
 * Seq with no way to remove an entry, the engine exposes no unregister at all - once per menu opened.
 * /players (and seven more call sites) opened a menu on every invocation, so repeated use grew that Seq,
 * and the PlayerData each closure captured, without bound for the life of the server.
 */
class OwnedMenuLeakTest {
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

    @Suppress("UNCHECKED_CAST")
    private fun menuListenerCount(): Int {
        val field = Menus::class.java.getDeclaredField("menuListeners")
        field.isAccessible = true
        return (field.get(null) as arc.struct.Seq<Any>).size
    }

    @Test
    fun repeatedlyOpeningAnOwnedMenuDoesNotGrowTheEnginesMenuListenerList() {
        val (player, _) = newPlayer()
        try {
            setPermission(player, "owner", true)

            // One call to let the lazy shared id register itself, whether or not an earlier test already
            // triggered it.
            clientCommand.handleMessage("/players", player)
            val before = menuListenerCount()

            repeat(20) {
                clientCommand.handleMessage("/players", player)
            }

            assertEquals(
                before,
                menuListenerCount(),
                "opening the same owned menu repeatedly must reuse one registered listener, not register a new one each time"
            )
        } finally {
            leavePlayer(player)
        }
    }
}
