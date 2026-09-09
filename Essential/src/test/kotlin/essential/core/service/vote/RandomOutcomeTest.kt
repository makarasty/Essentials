package essential.core.service.vote

import PluginTest.Companion.loadGame
import arc.Events
import arc.func.Cons
import arc.struct.ObjectMap
import arc.struct.Seq
import mindustry.game.EventType.WorldLoadEvent
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The delayed roll behind `vote random` has to stop when the map it was rolled on is replaced.
 *
 * It was scheduled through `Time.runTask`, which binds `Timer.schedule(Runnable, float)` and wraps its
 * argument, so the `Timer.Task` handed to it was never itself scheduled and the wrapper that was got
 * discarded. Nothing could cancel it, and three seconds is long enough for an rtv, an admin map load or
 * a game over to land first - after which the roll killed units, ran waves and filled cores on the map
 * that had just arrived.
 */
class RandomOutcomeTest {
    @BeforeTest
    fun setup() {
        loadGame()
    }

    @Test
    fun theTaskReturnedIsTheTaskThatWasScheduled() {
        var rolled = 0
        val task = scheduleRandomOutcome { rolled++ }
        try {
            assertTrue(task.isScheduled, "the outcome was not scheduled on arc's timer at all")
            task.run()
            assertEquals(1, rolled, "the outcome did not run")
        } finally {
            task.cancel()
        }
    }

    @Test
    fun aWorldLoadStopsTheOutcome() {
        var rolled = 0
        val task = scheduleRandomOutcome { rolled++ }
        try {
            Events.fire(WorldLoadEvent())
            assertFalse(task.isScheduled, "a world load did not unschedule the outcome")

            // Arc drops a one shot task from the timer's list before it posts it to the app thread, so a
            // world load can land when there is no longer anything to unschedule. This is that case, and
            // unscheduling alone would not cover it.
            task.run()
            assertEquals(0, rolled, "the outcome ran on the map that replaced the one it was rolled on")
        } finally {
            task.cancel()
        }
    }

    @Test
    fun theOutcomeDropsItsWorldLoadListenerWhenItRuns() {
        val before = worldLoadListeners()
        val task = scheduleRandomOutcome { }
        try {
            assertEquals(before + 1, worldLoadListeners(), "the outcome did not register a world load listener")

            task.run()

            // Every passed `vote random` registers one of these. Without this removal they accumulate for
            // the life of the process, and every map change walks all of them.
            assertEquals(before, worldLoadListeners(), "the outcome left its world load listener behind")
        } finally {
            task.cancel()
        }
    }

    /** Arc keeps its listeners in a private static map, and nothing on [Events] reports the count. */
    @Suppress("UNCHECKED_CAST")
    private fun worldLoadListeners(): Int {
        val field = Events::class.java.getDeclaredField("events").apply { isAccessible = true }
        val events = field.get(null) as ObjectMap<Any, Seq<Cons<*>>>
        return events.get(WorldLoadEvent::class.java)?.size ?: 0
    }
}
