package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import essential.common.database.data.getPlayerData
import essential.common.mapStartTime
import essential.common.offlinePlayers
import essential.common.timeSource
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import arc.Events
import mindustry.game.EventType.GameOverEvent
import mindustry.game.EventType.WorldLoadEvent
import mindustry.game.Team
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

class GameOverExpTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
    }

    /**
     * task-091: a player who leaves and rejoins used to leave a stale duplicate parked in
     * offlinePlayers forever, because nothing ever removed one on rejoin - the only add was at
     * playerLeave and a repo-wide grep found no remove/removeIf at all before this fix.
     */
    @Test
    fun rejoiningRemovesTheStaleOfflineEntry() {
        val (player, data) = newPlayer()
        try {
            leavePlayer(player)
            assertTrue(
                offlinePlayers.any { it.uuid == data.uuid },
                "leaving must park the player in offlinePlayers"
            )

            // Same uuid, a fresh connection - a real rejoin, not the same in-memory object.
            val rejoinPlayer = createPlayer()
            rejoinPlayer.con.uuid = data.uuid
            val rejoined = joinPlayer(rejoinPlayer)
            assertEquals(
                0,
                offlinePlayers.count { it.uuid == rejoined.uuid },
                "a rejoin must clear the leave-time snapshot - the player is online again, not offline"
            )

            // Leave a second time: without the fix this is the second unremoved add, so gameOver
            // would iterate this uuid twice and earn its match EXP twice.
            leavePlayer(rejoined.player)
            assertEquals(
                1,
                offlinePlayers.count { it.uuid == rejoined.uuid },
                "one leave-rejoin-leave cycle must leave exactly one offlinePlayers entry, not a duplicate"
            )
        } finally {
            offlinePlayers.removeIf { it.uuid == data.uuid }
        }
    }

    /**
     * A parked snapshot used to hold the leaver's own Player, and through its connection the socket
     * buffers, until game over. It now holds a stand-in that answers the same questions.
     */
    @Test
    fun theParkedSnapshotDoesNotHoldTheLeaversConnection() {
        val (player, data) = newPlayer()
        val con = player.con()
        val team = player.team()
        val x = player.x()
        try {
            leavePlayer(player)
            val parked = offlinePlayers.first { it.uuid == data.uuid }
            assertTrue(parked.player !== player, "the snapshot must not keep the leaver's Player")
            assertTrue(parked.player.con() !== con, "the snapshot must not keep the leaver's connection")
            assertEquals(data.uuid, parked.player.uuid())
            assertEquals(player.name(), parked.player.name())
            assertEquals(team, parked.player.team())
            assertEquals(x, parked.player.x(), "a random vote's fire still lands where the starter stood")
            assertEquals(player.locale(), parked.player.locale())
            assertEquals(con.address, parked.player.con().address)
            assertTrue(!parked.player.con().isConnected, "the stand-in must not look connected")
        } finally {
            offlinePlayers.removeIf { it.uuid == data.uuid }
        }
    }

    /**
     * task-093: currentUnitDestroyedCount/currentBuildDestroyedCount/currentBuildAttackCount fed
     * straight into earnEXP's score and were never zeroed anywhere in the repository, so a player
     * connected across several maps carried one map's counters into the next map's EXP calculation.
     */
    @Test
    fun worldLoadResetsTheCombatCountersEarnEXPReads() {
        val (player, data) = newPlayer()
        try {
            data.currentUnitDestroyedCount = 7
            data.currentBuildDestroyedCount = 3
            data.currentBuildAttackCount = 5

            Events.fire(WorldLoadEvent())

            assertEquals(0, data.currentUnitDestroyedCount, "currentUnitDestroyedCount must not survive a map load")
            assertEquals(0, data.currentBuildDestroyedCount, "currentBuildDestroyedCount must not survive a map load")
            assertEquals(0, data.currentBuildAttackCount, "currentBuildAttackCount must not survive a map load")
        } finally {
            leavePlayer(player)
        }
    }

    /**
     * task-087: earnEXP computed and mutated a departed player's exp in memory and the very next
     * line cleared offlinePlayers, with no .update() anywhere between - every leaver's match EXP
     * was computed and thrown away. This proves it now reaches the database.
     */
    @Test
    fun anOfflinePlayersMatchExpReachesTheDatabase() {
        val (player, data) = newPlayer()
        val previousPvp = Vars.state.rules.pvp
        val previousMapStart = mapStartTime
        try {
            data.player.team(Team.sharded)
            Vars.state.rules.pvp = true
            // earnEXP only scores once the match has run for five minutes.
            mapStartTime = timeSource.markNow().minus(6.minutes)

            leavePlayer(player)
            assertTrue(offlinePlayers.any { it.uuid == data.uuid }, "leaving must park the player in offlinePlayers")

            Events.fire(GameOverEvent(Team.sharded))

            val deadline = System.currentTimeMillis() + 10_000
            var reloaded = runBlocking { getPlayerData(data.uuid) }
            while ((reloaded == null || reloaded.exp == 0) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                reloaded = runBlocking { getPlayerData(data.uuid) }
            }

            assertTrue(
                (reloaded ?: fail("player row disappeared")).exp > 0,
                "EXP earned by an already-offline player must be persisted, not only mutated in memory"
            )
        } finally {
            Vars.state.rules.pvp = previousPvp
            mapStartTime = previousMapStart
            offlinePlayers.removeIf { it.uuid == data.uuid }
        }
    }

    /**
     * A leaver whose exit ends the match (autoPause + manageTeams) is parked in offlinePlayers before
     * GameOverEvent fires and is only dropped from players after it, so gameOver paid them twice. A
     * player already paid their defeat by recordPvpDefeat was also paid the loser's share again.
     */
    @Test
    fun matchExpIsPaidOncePerPlayer() {
        val (player, data) = newPlayer()
        val (_, specter) = newPlayer()
        val previousPvp = Vars.state.rules.pvp
        val previousMapStart = mapStartTime
        try {
            data.player.team(Team.sharded)
            specter.player.team(Team.crux)
            Vars.state.rules.pvp = true
            mapStartTime = timeSource.markNow().minus(6.minutes)
            offlinePlayers.add(data) // still in players, as during playerLeave's GameOverEvent
            pvpSpecters.add(specter.uuid)
            val exp = data.exp
            val expected = ((data.currentPlayTime + ExpTuning.PVP_BONUS) * data.expMultiplier).toInt()
            val specterExp = specter.exp

            Events.fire(GameOverEvent(Team.sharded))

            assertEquals(exp + expected, data.exp, "the winner's match EXP must be paid once")
            assertEquals(specterExp, specter.exp, "recordPvpDefeat already paid this player's defeat")
        } finally {
            Vars.state.rules.pvp = previousPvp
            mapStartTime = previousMapStart
            offlinePlayers.removeIf { it.uuid == data.uuid }
            pvpSpecters.remove(specter.uuid)
            leavePlayer(player)
            leavePlayer(specter.player)
        }
    }
}
