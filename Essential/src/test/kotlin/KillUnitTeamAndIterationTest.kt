import PluginTest.Companion.loadGame
import PluginTest.Companion.serverCommand
import PluginTest.Companion.waitUntil
import mindustry.content.UnitTypes
import mindustry.game.Team
import mindustry.gen.Groups
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task-077: both killunit variants iterated Groups.unit with Kotlin's own iterator while killing units
 * inside the loop body. The engine's unit group is an unordered array that swap-removes, so a plain
 * forEach/for loop skips whatever gets swapped into the slot just visited - the safe counterpart, used
 * by killall two commands above in the same file, is Groups.unit.each.
 *
 * task-078: the server console variant's counted branch also dropped the team filter entirely - it
 * killed matching units of every team regardless of the argument - and its "kill everything" guard
 * bound as `A || (B && team != null)` rather than `(A || B) && team != null`, so the null check the
 * author attached to the whole condition covered only its second half.
 *
 * Counts are taken as deltas from a baseline read at the start of each test, not absolute numbers -
 * a lingering player's own unit from another test class must not make this test flaky.
 */
class KillUnitTeamAndIterationTest {
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

    private fun spawnDaggers(team: Team, count: Int) {
        repeat(count) { i -> UnitTypes.dagger.spawn(team, 100f + i * 30f, 100f + i * 30f) }
    }

    private fun daggerCount(team: Team) = Groups.unit.count { it.type() == UnitTypes.dagger && it.team == team }

    private fun cleanup() {
        Groups.unit.filter { it.type() == UnitTypes.dagger }.forEach { it.remove() }
    }

    @Test
    fun serverKillUnit_countedBranchOnlyKillsTheNamedTeam() {
        val crux0 = daggerCount(Team.crux)
        val sharded0 = daggerCount(Team.sharded)
        spawnDaggers(Team.sharded, 6)
        spawnDaggers(Team.crux, 6)
        try {
            serverCommand.handleMessage("killunit dagger 4 crux")

            assertTrue(
                waitUntil(5000) { daggerCount(Team.crux) == crux0 + 2 },
                "exactly the requested amount of the named team's units should have died"
            )
            assertEquals(sharded0 + 6, daggerCount(Team.sharded), "an unrelated team must not lose units to a team-scoped killunit")
        } finally {
            cleanup()
        }
    }

    @Test
    fun killUnit_eachAvoidsSkippingUnitsTheEnginesSwapRemoveShifts() {
        spawnDaggers(Team.sharded, 12)
        try {
            // Uncounted and unfiltered: every dagger project-wide must die, so this holds regardless of
            // whatever else happens to exist in Groups.unit at the time.
            serverCommand.handleMessage("killunit dagger")

            assertTrue(
                waitUntil(5000) { Groups.unit.count { it.type() == UnitTypes.dagger } == 0 },
                "an uncounted killunit must kill every matching unit, not skip whatever the engine's swap-remove shifted into the visited slot"
            )
        } finally {
            cleanup()
        }
    }
}
