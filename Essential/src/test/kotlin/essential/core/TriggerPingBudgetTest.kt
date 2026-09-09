package essential.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The world-edit selection outline used to send one effect packet per perimeter tile, four times a
 * second, with the selection bounded only by the map.
 */
class TriggerPingBudgetTest {
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
