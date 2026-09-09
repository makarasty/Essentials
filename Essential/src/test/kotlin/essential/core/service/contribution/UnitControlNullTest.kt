package essential.core.service.contribution

import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import mindustry.content.UnitTypes
import mindustry.game.EventType.UnitControlEvent
import mindustry.game.EventType.UnitDestroyEvent
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Letting go of a unit fires UnitControlEvent with a null unit: InputHandler.unitControl takes its
 * clearUnit() branch and then fires the event with the same null it was given. arc.util.Nullable is
 * not an annotation Kotlin reads, so the handler's non-null read compiled to an assertion that threw
 * on the game thread for something every player does, in a module that is on by default.
 */
class UnitControlNullTest {
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

    @Test
    fun releasingAUnitDoesNotThrow() {
        // Without this the handler returns on its first line and the test passes with the defect intact.
        assertTrue(ContributionService.conf.enabled, "contribution scoring has to be on to reach the defect")

        val player = newPlayer()
        unitControl(UnitControlEvent(player.first, null))
    }

    /**
     * The control: it catches a guard written as an unconditional early return. It says nothing about
     * the null path, which only [releasingAUnitDoesNotThrow] covers.
     */
    @Test
    fun takingControlStillRecordsTheController() {
        assertTrue(ContributionService.conf.enabled, "contribution scoring has to be on to reach the handler")

        // getTotalRequirements is derived from whichever factory plan builds this type, so a content
        // change could make it empty; addScore returns early on zero and the assertion would then be
        // about nothing.
        val cost = UnitTypes.dagger.getTotalRequirements().sumOf { it.amount }
        assertTrue(cost > 0, "the test unit has to cost something or the loss penalty is zero")

        val player = newPlayer()
        // spawn() calls Unit.add(), so this dagger joins Groups.unit and ticks for the rest of the JVM
        // unless it is removed. The suite shares one world across classes.
        val unit = UnitTypes.dagger.spawn(10f, 10f)
        try {
            player.second.currentContribution = 0.0

            unitControl(UnitControlEvent(player.first, unit))
            // The controller map is private, and the loss penalty unitDestroy charges it is the only
            // way to read it back.
            unitDestroy(UnitDestroyEvent(unit))

            assertEquals(
                -cost.toDouble(),
                player.second.currentContribution,
                "a controlled unit that dies must charge its controller its build cost"
            )
        } finally {
            unit.remove()
        }
    }
}
