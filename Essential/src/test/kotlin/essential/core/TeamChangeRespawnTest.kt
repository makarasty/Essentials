package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import essential.common.util.changeTeam
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Team
import mindustry.gen.Call
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A player's team used to be changed with a bare `Player.team(Team)`, which only repaints the unit
 * the player already sits in. Every team change this plugin makes lands after the engine has already
 * spawned that unit at the core of the team it picked itself, so the player ended up wearing the new
 * team's colours while standing inside the old team's base - the PvP join complaint that started
 * task-081, and the reason a map change (which respawns everyone) "fixed" it.
 */
class TeamChangeRespawnTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
    }

    @Test
    fun changeTeam_respawnsThePlayerAtTheNewTeamsCore() {
        val player = createPlayer()
        val coreTile = Vars.world.tile(Vars.world.width() - 20, Vars.world.height() - 20)
        Call.setTile(coreTile, Blocks.coreShard, Team.crux, 0)
        val core = assertNotNull(coreTile.build, "the test core should have been built")

        try {
            player.changeTeam(Team.crux)
            // deathTimer is armed past the 60-tick respawn delay, so one update is enough.
            repeat(3) { player.update() }

            assertEquals(Team.crux, player.team())
            val unit = assertNotNull(player.unit(), "the player should have been respawned")
            assertEquals(Team.crux, unit.team())
            assertTrue(
                unit.dst(core) < Vars.tilesize * 4f,
                "the player should stand at their own core, not ${unit.x}, ${unit.y}"
            )
        } finally {
            leavePlayer(player)
            Call.setTile(coreTile, Blocks.air, Team.derelict, 0)
        }
    }
}
