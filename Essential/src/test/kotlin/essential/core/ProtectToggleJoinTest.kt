package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.pumpApp
import arc.Events
import arc.func.Cons
import essential.common.event.CustomEvents
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Switching module.protect off in a live config.yaml registers core's fallback join handler, while the
 * protect module's own join handler, registered at boot, stays. Both used to load the joining player's
 * data: the player was loaded twice, or - when the two reads raced - the second found the row the first
 * had just created under the player's own name and kicked them as a duplicate nickname.
 */
class ProtectToggleJoinTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
    }

    @Test
    fun protectSwitchedOffAtRuntime_loadsAJoiningPlayerOnce() {
        val previous = Main.conf
        val loads = AtomicInteger()
        var uuid: String? = null
        val listener = Cons<CustomEvents.PlayerDataLoad> { if (it.playerData.uuid == uuid) loads.incrementAndGet() }
        Events.on(CustomEvents.PlayerDataLoad::class.java, listener)
        try {
            Main.conf = previous.copy(module = previous.module.copy(protect = false))
            syncProtectFallbackJoinListener()

            val player = createPlayer()
            uuid = player.uuid()
            joinPlayer(player)
            // Give a second handler's load, had there been one, time to land.
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                pumpApp()
                Thread.sleep(16)
            }

            assertFalse(player.con.kicked, "the joining player was kicked, as a duplicate of themselves")
            assertEquals(1, loads.get(), "the joining player's data was loaded by two handlers")
            leavePlayer(player)
        } finally {
            Events.remove(CustomEvents.PlayerDataLoad::class.java, listener)
            Main.conf = previous
            syncProtectFallbackJoinListener()
        }
    }
}
