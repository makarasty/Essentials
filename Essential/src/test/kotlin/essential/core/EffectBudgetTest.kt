package essential.core

import PluginTest.Companion.loadGame
import arc.graphics.Color
import essential.core.service.effect.EffectSystem
import mindustry.content.Fx
import mindustry.gen.Player
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectBudgetTest {
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

    /**
     * [players] emitting players with [each] effects apiece, every effect tagged with a distinct
     * rotate value so a pass can be traced back to what it carried.
     */
    private fun groups(players: Int, each: Int): List<List<EffectSystem.EffectPos>> {
        val player = Player.create()
        var tag = 0f
        return List(players) {
            List(each) { EffectSystem.EffectPos(player, Fx.freezing, tag++, Color.white) }
        }
    }

    @Test
    fun onePassStaysUnderThePacketCeiling() {
        // 60 players at level 200 emit 8 effects each, and all 60 are watching.
        val targets = 60

        val packets = EffectSystem().nextSlice(groups(60, 8), targets).size * targets

        assertTrue(
            packets in 1..EffectSystem.MAX_PACKETS_PER_RUN,
            "One pass sent $packets packets, ceiling is ${EffectSystem.MAX_PACKETS_PER_RUN}."
        )
    }

    @Test
    fun everyPlayersEffectsAreShownAcrossSuccessivePasses() {
        val groups = groups(60, 8)
        val system = EffectSystem()

        val shown = mutableSetOf<Float>()
        repeat(20) { system.nextSlice(groups, 60).forEach { shown.add(it.rotate) } }

        assertEquals(
            480,
            shown.size,
            "The ceiling must delay effects, not drop them: only ${shown.size} of 480 were ever sent."
        )
    }

    @Test
    fun aPassNeverCutsAPlayerEffectsInHalf() {
        val groups = groups(60, 8)
        val system = EffectSystem()

        repeat(10) {
            val slice = system.nextSlice(groups, 60)
            assertEquals(
                0,
                slice.size % 8,
                "A pass carried ${slice.size} effects, which is not a whole number of 8-effect shapes."
            )
        }
    }

    @Test
    fun aSmallBufferIsSentWhole() {
        assertEquals(8, EffectSystem().nextSlice(groups(1, 8), 6).size)
    }

    @Test
    fun oneOversizedGroupIsStillSent() {
        // 2000 / 4 viewers allows 500, and this one player alone wants 800.
        assertEquals(800, EffectSystem().nextSlice(groups(2, 800), 4).size)
    }

    @Test
    fun nothingIsSentWithNoViewers() {
        assertTrue(EffectSystem().nextSlice(groups(1, 8), 0).isEmpty())
    }
}
