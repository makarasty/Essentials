package essential.core.service.vote

import PluginTest.Companion.loadGame
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The repeating decay left behind by the `vote random` fire outcome has to be stoppable.
 *
 * Before the fix it was a `java.util.TimerTask` handed to arc's `Timer.schedule`, which bound the
 * `Runnable` overload, wrapped the object in an arc task of its own and returned that; the wrapper was
 * discarded, so the object's `cancel()` cancelled a `java.util.Timer` scheduling that had never
 * happened. The decay then ran every ten seconds until the process was restarted, across map changes,
 * and a second roll of the same outcome added another copy.
 */
class FireDecayTest {
    @BeforeTest
    fun setup() {
        loadGame()
    }

    @Test
    fun cancelStopsTheDecay() {
        val task = scheduleFireDecay {}
        try {
            assertTrue(task.isScheduled, "the decay was not scheduled on arc's timer at all")
            task.cancel()
            assertFalse(task.isScheduled, "cancelling the decay did not unschedule it")
        } finally {
            task.cancel()
        }
    }

    @Test
    fun theDecayStopsWhenItsCountdownRunsOut() {
        val task = scheduleFireDecay(ticks = 2) {}
        try {
            // The task is live on arc's timer, so a scheduler run can land between these calls. That
            // only spends the countdown sooner, which is why nothing here asserts the state in between.
            repeat(2) { if (task.isScheduled) task.run() }
            assertFalse(task.isScheduled, "the decay kept repeating after its countdown reached zero")
        } finally {
            task.cancel()
        }
    }

    @Test
    fun theSupplyDropFiresOnceAtTheHalfWayMark() {
        var supplies = 0
        val task = scheduleFireDecay(ticks = 4) { supplies++ }
        try {
            repeat(4) { task.run() }
            assertEquals(1, supplies, "the half way supply drop did not fire exactly once")
        } finally {
            task.cancel()
        }
    }
}
