package essential.core

import essential.common.database.data.plugin.WarpBlock
import essential.common.database.data.plugin.WarpCount
import essential.common.database.data.plugin.WarpZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two per-cycle costs the ping loop and the world-edit outline used to leave uncapped: one
 * blocking ping per warp entry rather than per distinct server, and one effect packet per
 * perimeter tile of a selection the map alone bounds.
 */
class TriggerPingBudgetTest {
    private fun block(ip: String, port: Int) =
        WarpBlock("map", 1, 1, "router", 1, ip, port, "d")

    private fun count(ip: String, port: Int) = WarpCount("map", 1, ip, port)

    private fun zone(ip: String, port: Int) = WarpZone("map", 1, 2, false, ip, port)

    @Test
    fun oneServerInEveryListIsPingedOnce() {
        val targets = Trigger.pingTargets(
            listOf(block("10.0.0.1", 6567)),
            listOf(count("10.0.0.1", 6567)),
            listOf(zone("10.0.0.1", 6567)),
        )

        assertEquals(setOf("10.0.0.1" to 6567), targets)
    }

    @Test
    fun differentPortsOnOneHostStayDistinct() {
        val targets = Trigger.pingTargets(
            listOf(block("10.0.0.1", 6567), block("10.0.0.1", 6568)),
            listOf(count("10.0.0.2", 6567)),
            emptyList(),
        )

        assertEquals(3, targets.size)
    }

    @Test
    fun duplicatesWithinOneListCollapse() {
        val targets = Trigger.pingTargets(
            List(20) { block("10.0.0.1", 6567) },
            emptyList(),
            emptyList(),
        )

        assertEquals(1, targets.size, "twenty warp blocks on one server are still one server to ping")
    }

    @Test
    fun noSelectionOutlineExceedsTheCap() {
        // Every span a selection can have on the largest map the engine will load, not just the
        // 500-wide one the finding named: the step is a truncating division, so the worst case is
        // not at either end of the range.
        val worst = (0..2000).maxOf { Trigger.outlineMarks(0, it).count() }

        assertTrue(
            worst <= Trigger.OUTLINE_MARKS,
            "one edge drew $worst marks, the cap is ${Trigger.OUTLINE_MARKS}"
        )
        assertTrue(Trigger.outlineMarks(0, 499).count() > 1, "the outline still has to show the shape")
    }

    @Test
    fun aSmallSelectionOutlineIsUnchanged() {
        assertEquals(
            (0..5).toList(),
            Trigger.outlineMarks(0, 5).toList(),
            "below the cap every perimeter tile is still marked"
        )
        assertEquals(listOf(7), Trigger.outlineMarks(7, 7).toList())
    }
}
