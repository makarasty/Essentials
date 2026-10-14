package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.loadGame
import arc.struct.Queue
import essential.common.database.data.createTemporaryPlayerData
import essential.common.log.FileAppender
import essential.core.service.achievements.APMTracker
import essential.core.service.effect.EffectSystem
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.entities.units.BuildPlan
import mindustry.net.NetConnection
import java.nio.file.Files
import java.util.regex.Pattern
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The hot-path shortcuts must answer exactly what the code they replaced answered. */
class HotPathEquivalenceTest {
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

    private fun queue(vararg plans: BuildPlan) = Queue<BuildPlan>().apply { plans.forEach { addLast(it) } }

    @Test
    fun aRotatedPlanQueueKeepsItsFingerprint() {
        val q = queue(
            BuildPlan(1, 2, 0, Blocks.copperWall),
            BuildPlan(3, 4, 1, Blocks.conveyor),
            BuildPlan(5, 6)
        )
        val before = APMTracker.planFingerprint(q)
        // What BuilderComp does every tick it cannot build the head plan.
        q.addLast(q.removeFirst())
        assertEquals(before, APMTracker.planFingerprint(q), "a reordered queue holds the same plans")

        assertNotEquals(before, APMTracker.planFingerprint(queue(BuildPlan(1, 2, 0, Blocks.copperWall), BuildPlan(3, 4, 1, Blocks.conveyor))))
        assertNotEquals(before, APMTracker.planFingerprint(queue(BuildPlan(1, 2, 0, Blocks.copperWall), BuildPlan(3, 4, 2, Blocks.conveyor), BuildPlan(5, 6))))
        // Two plans trading a coordinate must not cancel out in the sum.
        assertNotEquals(
            APMTracker.planFingerprint(queue(BuildPlan(1, 2, 0, Blocks.copperWall), BuildPlan(3, 4, 0, Blocks.copperWall))),
            APMTracker.planFingerprint(queue(BuildPlan(3, 2, 0, Blocks.copperWall), BuildPlan(1, 4, 0, Blocks.copperWall)))
        )
    }

    @Test
    fun theConstructNameCheckMatchesTheRegexItReplaced() {
        val regex = Pattern.compile("^build\\d{1,2}$")
        val names = Vars.content.blocks().map { it.name } + listOf(
            "", "build", "build0", "build9", "build16", "build99", "build100", "build1a", "Build1",
            "xbuild1", "build١", "build1\n", "buil1", "build-1"
        )
        for (name in names) {
            assertEquals(regex.matcher(name).matches(), isConstructBlockName(name), "'$name'")
        }
        assertTrue(isConstructBlockName("build16"))
    }

    private fun viewer(x: Float, y: Float, w: Float, h: Float) = object : NetConnection("127.0.0.1") {
        override fun send(`object`: Any?, reliable: Boolean) = Unit
        override fun close() = Unit
    }.apply { viewX = x; viewY = y; viewWidth = w; viewHeight = h }

    @Test
    fun anEffectFarOutsideTheViewIsNotSent() {
        val system = EffectSystem()
        val clip = Fx.freezing.clip
        val con = viewer(1000f, 1000f, 800f, 480f)

        assertTrue(system.inView(con, 1000f, 1000f, clip))
        assertTrue(system.inView(con, 1400f + clip, 1240f + clip, clip), "the edge plus the clip still counts")
        assertTrue(system.inView(con, 1400f + clip + 63f, 1000f, clip), "inside the camera-lag margin")
        assertFalse(system.inView(con, 4000f, 1000f, clip))
        assertFalse(system.inView(con, 1000f, 100f, clip))
        // Nothing reported yet, or no connection at all: send as before.
        assertTrue(system.inView(viewer(0f, 0f, 0f, 0f), 4000f, 4000f, clip))
        assertTrue(system.inView(null, 4000f, 4000f, clip))
    }

    @Test
    fun theTrackedLogLengthIsTheFileLength() {
        val file = Files.createTempFile("appender", ".log").toFile()
        try {
            file.writeText("existing")
            val appender = FileAppender(file)
            appender.write("plain line")
            appender.write("многобайтовая строка ✓")
            assertEquals(file.length(), appender.length())
            appender.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun aPlaceholderPlayerIsStillPerInstance() {
        val real = createPlayer()
        val data = createTemporaryPlayerData(real)
        assertSame(real, data.player)

        val snapshot = data.copy()
        val other = data.copy()
        assertSame(snapshot.player, snapshot.player, "the placeholder is made once, not per read")
        assertNotSame(snapshot.player, other.player, "two rows must not share one placeholder")
        assertNotSame(real, snapshot.player)
    }
}
