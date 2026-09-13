package essential.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The XP thresholds are now read from a table built once, rather than re-summed from level zero on
 * every call. Same numbers, or every player's level moves.
 */
class ExpCurveTest {
    /** What [Commands.Exp.calculateFullTargetXp] did before the table, summed in the same order. */
    private fun summed(level: Int): Double {
        var total = 0.0
        for (i in 0..level) total += 750 + 750 * i.toDouble().pow(1.06)
        return total
    }

    @Test
    fun the_table_carries_the_same_totals_as_the_summation() {
        // Past the end of the table too, where the old summation still runs.
        for (level in 0..1200) {
            assertEquals(summed(level), Commands.Exp.calculateFullTargetXp(level), 1e-6, "level $level")
        }
    }

    @Test
    fun a_level_is_the_first_threshold_that_covers_the_xp() {
        // The do/while this replaced always ran once, so nobody is level 0.
        assertEquals(1, Commands.Exp.calculateLevel(0))
        for (xp in listOf(1, 1_000, 5_000, 50_000, 500_000, 5_000_000)) {
            val level = Commands.Exp.calculateLevel(xp)
            assertTrue(Commands.Exp.calculateFullTargetXp(level) >= xp, "level $level is short of $xp")
            assertTrue(
                level == 1 || Commands.Exp.calculateFullTargetXp(level - 1) < xp,
                "level $level is one too many for $xp",
            )
        }
    }
}
