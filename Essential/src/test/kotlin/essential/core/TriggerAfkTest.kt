package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import essential.common.database.data.PlayerData
import essential.common.players
import mindustry.game.Team
import mindustry.gen.Player
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two halves of the AFK handling that the shipped configuration got wrong: an empty
 * `feature.afk.server` took the teleport branch instead of the documented kick, and a player with
 * no unit - every PvP loser this plugin moves to [Team.derelict] - was read as active.
 */
class TriggerAfkTest {
    companion object {
        private var done = false
        private lateinit var testPlayer: Player
        private lateinit var testData: PlayerData
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
    fun theShippedEmptyServerMeansKick() {
        // The value an operator gets by enabling feature.afk and touching nothing else.
        assertNull(
            Trigger.afkTarget(CoreConfig().feature.afk.server),
            "the shipped default is documented as disabling the teleport, so it has to select the kick branch"
        )
        assertNull(Trigger.afkTarget(null))
        assertNull(Trigger.afkTarget("   "))
    }

    @Test
    fun aTargetThatCannotBeReachedIsNoTarget() {
        assertNull(Trigger.afkTarget(":6567"), "a value with no host is the same empty host as the default")
        assertNull(Trigger.afkTarget("hub.example.com:"), "the port used to reach toInt and throw")
        assertNull(Trigger.afkTarget("hub.example.com: 80x"))
        assertNull(Trigger.afkTarget("hub.example.com:99999"), "no such port exists")
        assertNull(Trigger.afkTarget("hub.example.com:0"))
    }

    @Test
    fun aConfiguredServerStillTeleports() {
        assertEquals("hub.example.com" to 6567, Trigger.afkTarget("hub.example.com"))
        assertEquals("hub.example.com" to 7000, Trigger.afkTarget("hub.example.com:7000"))
        assertEquals("hub.example.com" to 7000, Trigger.afkTarget("  hub.example.com:7000  "))
        // Unchanged from before the fix: everything past the second segment was already dropped.
        assertEquals("hub.example.com" to 6567, Trigger.afkTarget("hub.example.com:6567:9"))
    }

    @Test
    fun aDeadPlayerIsIdleUntilThePlayerPansTheCamera() {
        val originalUnit = testPlayer.unit()
        val originalTeam = testPlayer.team()
        val tracked = players.remove(testData)
        try {
            // Team.derelict has no cores, so PlayerComp.update never respawns this player and
            // unit() stays null - the state a PvP defeat leaves them in for the rest of the round.
            testPlayer.team(Team.derelict)
            testPlayer.clearUnit()
            assertNull(testPlayer.unit(), "the reproduction needs a player with no unit")

            val con = testPlayer.con()
            con.viewX = 100f
            con.viewY = 200f
            assertEquals(
                300f,
                Trigger.activityMark(testData),
                "a dead player's pointer is pinned at 0,0 by NetClient.sync, so the camera is the only mark left"
            )

            testData.mousePosition = Trigger.activityMark(testData)
            assertTrue(
                Trigger.isAfkCandidate(testData),
                "a player who can never move or mine again, and is not panning, is idle"
            )

            con.viewX = 140f
            assertFalse(
                Trigger.isAfkCandidate(testData),
                "panning the camera is the only activity a dead player can still show, so it has to count"
            )
        } finally {
            testPlayer.team(originalTeam)
            if (originalUnit != null) testPlayer.unit(originalUnit)
            if (tracked) players.add(testData)
        }
    }
}
