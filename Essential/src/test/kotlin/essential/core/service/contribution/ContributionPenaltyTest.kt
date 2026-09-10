package essential.core.service.contribution

import PluginTest.Companion.newPlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.updateTick
import essential.common.database.data.createTemporaryPlayerData
import essential.common.offlinePlayers
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.EventType.BlockBuildEndEvent
import mindustry.world.Build
import mindustry.world.Tile
import mindustry.world.blocks.ConstructBlock
import mindustry.world.blocks.production.Drill
import java.util.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * task-068: the turret exemption from the build resource penalty was a name-substring test, and no
 * vanilla turret's name contains the substring "turret" (duo, salvo, lancer...) - only the two repair
 * turrets do. Every real turret paid its full build cost as a negative score, the opposite of what the
 * config comment promises.
 *
 * task-070/task-072: the self-deconstruction penalty is exercised through a real ConstructBuild swap
 * (essential.core.service.contribution.ContributionEvents.blockBuildEnd) - the engine replaces a drill
 * or crafter with a ConstructBlock proxy before BlockBuildEndEvent(breaking=true) fires, so reading the
 * output rate off the event's own build always saw 0.0. This exercises the same path a live deconstruct
 * takes (Build.beginBreak swaps the tile, ConstructBlock.deconstructFinish fires the event), rather than
 * a synthetic breaking event, so a fix that only special-cases the test's own event shape would not pass.
 */
class ContributionPenaltyTest {
    companion object {
        private var done = false

        private fun freeTile(): Tile {
            // A fresh position each time, away from whatever earlier tests in this shared world left
            // behind - the world is 100x100 and this suite places a handful of blocks at most.
            val random = Random()
            while (true) {
                val tile = Vars.world.tile(2 + random.nextInt(90), 2 + random.nextInt(90))
                if (tile != null && tile.block() === Blocks.air) return tile
            }
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
    fun aRealTurretIsExemptFromTheBuildPenalty() {
        // Without this, blockBuildEnd() returns on its first line and the test passes with the
        // pre-fix substring test still intact - it would not have caught the defect either.
        assertTrue(ContributionService.conf.enabled, "contribution scoring has to be on to reach isPenaltyExempt")

        val wasInfinite = Vars.state.rules.infiniteResources
        Vars.state.rules.infiniteResources = false
        try {
            val player = newPlayer()
            val tile = freeTile()
            tile.setBlock(Blocks.duo, player.first.team())
            player.second.currentContribution = 0.0

            blockBuildEnd(BlockBuildEndEvent(tile, player.first.unit(), player.first.team(), false, null))

            assertEquals(
                0.0,
                player.second.currentContribution,
                "duo is a real turret (Category.turret) and its name does not contain 'turret' - it must " +
                    "still be exempt from the build resource penalty"
            )
        } finally {
            Vars.state.rules.infiniteResources = wasInfinite
        }
    }

    @Test
    fun aWallStaysExemptByNameSubstring() {
        assertTrue(ContributionService.conf.enabled, "contribution scoring has to be on to reach isPenaltyExempt")

        val wasInfinite = Vars.state.rules.infiniteResources
        Vars.state.rules.infiniteResources = false
        try {
            val player = newPlayer()
            val tile = freeTile()
            tile.setBlock(Blocks.copperWall, player.first.team())
            player.second.currentContribution = 0.0

            blockBuildEnd(BlockBuildEndEvent(tile, player.first.unit(), player.first.team(), false, null))

            assertEquals(
                0.0,
                player.second.currentContribution,
                "copper-wall must stay exempt through the untouched name-substring half of the check"
            )
        } finally {
            Vars.state.rules.infiniteResources = wasInfinite
        }
    }

    @Test
    fun aNonExemptProducerStillPaysTheBuildPenalty() {
        val wasInfinite = Vars.state.rules.infiniteResources
        Vars.state.rules.infiniteResources = false
        try {
            val player = newPlayer()
            val tile = freeTile()
            tile.setBlock(Blocks.mechanicalDrill, player.first.team())
            player.second.currentContribution = 0.0

            blockBuildEnd(BlockBuildEndEvent(tile, player.first.unit(), player.first.team(), false, null))

            assertTrue(
                player.second.currentContribution < 0.0,
                "mechanical-drill is production, not turret/wall/conveyor/duct, and must still pay its " +
                    "build resource penalty - saw ${player.second.currentContribution}"
            )
        } finally {
            Vars.state.rules.infiniteResources = wasInfinite
        }
    }

    /**
     * task-070 and task-072 together, driven through a real deconstruction rather than a synthetic event:
     * a live drill is polled once (also exercising task-072's owner lookup, resolved fresh from a real
     * online PlayerData plus a decoy offline row under the same uuid), then torn down through the actual
     * engine path that replaces it with a ConstructBlock proxy before the penalty is charged.
     */
    @Test
    fun selfDeconstructingAProducingDrillChargesItsLastObservedOutput() {
        val wasInfinite = Vars.state.rules.infiniteResources
        Vars.state.rules.infiniteResources = false
        val player = newPlayer()
        // A decoy row under the same uuid in offlinePlayers: task-072's fix must resolve the online row,
        // not this one, or the score below lands on the wrong PlayerData and both assertions go green
        // for the wrong reason - onlineBefore would never move.
        val decoy = createTemporaryPlayerData(player.first)
        offlinePlayers.add(decoy)
        try {
            val tile = freeTile()
            // Guarantee ore under every tile the (2x2) drill occupies, rather than depending on whatever
            // the shared test map happens to have at a random position.
            for (dx in 0 until Blocks.mechanicalDrill.size) {
                for (dy in 0 until Blocks.mechanicalDrill.size) {
                    Vars.world.tile(tile.x + dx, tile.y + dy)?.setOverlay(Blocks.oreCopper)
                }
            }
            tile.setBlock(Blocks.mechanicalDrill, player.first.team())
            val build = tile.build as? Drill.DrillBuild
            assertNotNull(build, "setBlock(mechanicalDrill) must produce a DrillBuild")

            // Let onProximityUpdate find the ore and warmup ramp up (warmupSpeed = 0.015/tick).
            updateTick(150)
            assertNotNull(build.dominantItem, "the drill must have found copper before this test can mean anything")
            assertTrue(build.warmup > 0f, "the drill must have warmed up before this test can mean anything")

            blockBuildEnd(BlockBuildEndEvent(tile, player.first.unit(), player.first.team(), false, null))
            decoy.currentContribution = 0.0
            player.second.currentContribution = 0.0

            pollProduction()
            val onlineAfterPoll = player.second.currentContribution
            assertTrue(
                onlineAfterPoll > 0.0,
                "polling a warmed-up, ore-fed drill must score its online owner - saw $onlineAfterPoll"
            )
            assertEquals(
                0.0, decoy.currentContribution,
                "the offline decoy under the same uuid must not be the one scored - online must win"
            )

            // Real deconstruction: swap to ConstructBuild (as a live server would), then finish it - the
            // same two engine calls Build.beginBreak's non-instant path makes, without waiting out the
            // real multi-tick countdown.
            Build.beginBreak(player.first.unit(), player.first.team(), tile.x.toInt(), tile.y.toInt())
            assertTrue(
                tile.build is ConstructBlock.ConstructBuild,
                "beginBreak must have swapped the tile to a ConstructBuild before this test can mean anything"
            )
            val beforeDeconstruct = player.second.currentContribution
            ConstructBlock.deconstructFinish(tile, Blocks.mechanicalDrill, player.first.unit())

            assertTrue(
                player.second.currentContribution < beforeDeconstruct,
                "self-deconstructing a producing drill must charge its last observed output as a penalty, " +
                    "not 0.0 read off the ConstructBlock proxy the event actually carries - before " +
                    "$beforeDeconstruct, after ${player.second.currentContribution}"
            )
        } finally {
            offlinePlayers.remove(decoy)
            Vars.state.rules.infiniteResources = wasInfinite
        }
    }
}
