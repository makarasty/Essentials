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

    /**
     * The engine asks its assigner at connect, before the first spawn, and at the end of every map
     * change, after WorldLoadEvent - where it used to overwrite the teams worldLoad had just planned.
     * The fallback stands in for the engine's own pick so every assertion is decided by the plugin.
     */
    @Test
    fun assigner_picksThePluginsTeamBeforeTheEngineDoes() {
        assertTrue(Vars.netServer.assigner is PvpTeamAssigner, "the plugin's assigner should be installed")

        val player = createPlayer()
        val uuid = player.uuid()
        val coreTile = Vars.world.tile(Vars.world.width() - 20, Vars.world.height() - 20)
        // Not crux: it is the wave team on this map, which PvP never hands out.
        Call.setTile(coreTile, Blocks.coreShard, Team.blue, 0)
        val assigner = PvpTeamAssigner { _, _ -> Team.green }
        val conf = Main.conf
        val wasPvp = Vars.state.rules.pvp
        fun pvp(autoTeam: Boolean = false, spector: Boolean = false) {
            Main.conf = conf.copy(feature = conf.feature.copy(pvp = conf.feature.pvp.copy(autoTeam = autoTeam, spector = spector)))
        }

        try {
            Vars.state.rules.pvp = false
            pvpPlayer[uuid] = Team.blue
            assertEquals(Team.green, assigner.assign(player, emptyList()), "outside PvP the engine decides")

            Vars.state.rules.pvp = true
            pvp()
            assertEquals(Team.blue, assigner.assign(player, emptyList()), "a planned team with a core wins")

            pvpPlayer[uuid] = Team.malis
            assertEquals(Team.green, assigner.assign(player, emptyList()), "a planned team without a core does not")

            pvpPlayer.remove(uuid)
            pvp(autoTeam = true)
            val picked = assertNotNull(selectAutoTeam(uuid), "the map should have a team autoTeam can pick")
            assertEquals(picked, assigner.assign(player, emptyList()), "autoTeam picks for a fresh join")
            assertEquals(picked, pvpConnectPicks[uuid], "and records it, so attachPlayerData keeps it")
            assertEquals(null, pvpPlayer[uuid], "but not as a remembered team: it was made before the permissions were known")

            pvpSpecters.add(uuid)
            pvp(spector = true)
            assertEquals(Team.derelict, assigner.assign(player, emptyList()), "a defeated player stays a spectator")
        } finally {
            Main.conf = conf
            Vars.state.rules.pvp = wasPvp
            pvpPlayer.remove(uuid)
            pvpConnectPicks.remove(uuid)
            pvpSpecters.remove(uuid)
            leavePlayer(player)
            Call.setTile(coreTile, Blocks.air, Team.derelict, 0)
        }
    }
}
