package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import arc.Events
import essential.common.database.data.PlayerData
import essential.common.players
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.LabelCallPacket
import mindustry.gen.Player
import mindustry.net.Net
import mindustry.net.NetConnection
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `/track` is in the default permission group and its label broadcast ran on every `Trigger.update`:
 * one label to the watching player for every player on the server, sixty times a second, so several
 * players with it on cost O(players^2) packets per tick. Both neighbouring per-frame handlers in the
 * same loop carry a rate gate; this one did not.
 */
class TrackBroadcastTest {
    companion object {
        private var done = false
        private lateinit var testPlayer: Player
        private lateinit var testData: PlayerData

        private const val TICKS = 60
        private const val GATE = 15
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            val joined = newPlayer()
            testPlayer = joined.first
            testData = joined.second
            done = true
        }
    }

    @Test
    fun trackDoesNotBroadcastOnEveryTick() {
        val originalCon = testPlayer.con

        // Call.label writes nothing at all unless the engine thinks it is hosting, and the test harness
        // never hosts. These are the two fields Net.server() reads.
        val serverField = Net::class.java.getDeclaredField("server").apply { isAccessible = true }
        val activeField = Net::class.java.getDeclaredField("active").apply { isAccessible = true }
        val wasServer = serverField.getBoolean(Vars.net)
        val wasActive = activeField.getBoolean(Vars.net)

        val counting = object : NetConnection(originalCon.address) {
            var labels = 0

            override fun send(`object`: Any?, reliable: Boolean) {
                if (`object` is LabelCallPacket) labels++
            }

            override fun close() = Unit
        }
        counting.uuid = originalCon.uuid
        counting.usid = originalCon.usid

        try {
            // The listener returns early while any tracked player is stale, so flush that first,
            // otherwise this test would pass without ever reaching the tracking block.
            Events.fire(EventType.Trigger.update)
            assertTrue(
                players.any { it.uuid == testPlayer.uuid() },
                "the test player has to be tracked for the listener to reach its mouse tracking block"
            )

            testPlayer.con = counting
            testData.mouseTracking = true
            serverField.setBoolean(Vars.net, true)
            activeField.setBoolean(Vars.net, true)

            // The gate counts listener invocations, so exactly TICKS / GATE of any run of TICKS
            // consecutive ticks fire, whatever phase the counter happens to start on.
            repeat(TICKS) {
                Events.fire(EventType.Trigger.update)
            }

            val watched = Groups.player.size()
            val ceiling = (TICKS / GATE + 1) * watched

            assertTrue(counting.labels > 0, "the tracking block never ran, so this measures nothing")
            assertTrue(
                counting.labels <= ceiling,
                "$TICKS ticks of mouse tracking sent ${counting.labels} labels to one player, " +
                    "ceiling is $ceiling for $watched players on a $GATE tick gate"
            )
        } finally {
            testData.mouseTracking = false
            testPlayer.con = originalCon
            serverField.setBoolean(Vars.net, wasServer)
            activeField.setBoolean(Vars.net, wasActive)
        }
    }
}
