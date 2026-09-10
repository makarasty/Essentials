package essential.core.service.vote

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import essential.common.isVoting
import essential.core.VoteData
import essential.core.VoteType
import mindustry.Vars
import mindustry.game.Team
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ruled in answers/9-2.md and broadcast section 30: `check()`'s table starts `1 -> 1` and the
 * constructor pre-seeds `voted` with the starter, so on a PvP map a starter alone on their team
 * passed `kick`/`gg` on the first tick with nobody else's consent - and the same shape let one
 * active player votekick the only other, idle, player on a non-PvP server. The fix is two changes
 * that stand together: [VoteData.team] defaults to the starter's own team on a PvP map (so all
 * seven vote types are team-scoped, not just the two that used to hand-write it), and `check()`
 * floors its threshold at 2 unless `players.size == 1` - the one case (a lone player on the whole
 * server voting `map`, the `solo` hatch in Commands.kt) where a floor of 2 would be a regression
 * rather than a fix.
 */
class VoteElectorateTest {
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

    @AfterTest
    fun cleanup() {
        isVoting = false
    }

    @Test
    fun aVoteDataDefaultsItsTeamToTheStartersOwnTeamOnAPvpMap() {
        val starter = newPlayer()
        val wasPvp = Vars.state.rules.pvp
        val startingTeam = starter.first.team()
        try {
            Vars.state.rules.pvp = true
            starter.first.team(Team.crux)

            val voteData = VoteData(type = VoteType.GameOver, starter = starter.second)

            assertEquals(
                Team.crux,
                voteData.team,
                "a PvP vote must default its electorate to the starter's own team, not defaultTeam"
            )
        } finally {
            Vars.state.rules.pvp = wasPvp
            starter.first.team(startingTeam)
            leavePlayer(starter.first)
        }
    }

    @Test
    fun aLonePvpTeamPlayerCanNoLongerPassAVoteWithOnlyTheirOwnSeededVote() {
        val starter = newPlayer()
        // A bystander on a different team keeps the server from being genuinely solo, so this
        // isolates the floor from the `players.size == 1` escape hatch below.
        val bystander = newPlayer()
        val wasPvp = Vars.state.rules.pvp
        val startingTeams = listOf(starter, bystander).map { it.first.team() }
        try {
            Vars.state.rules.pvp = true
            starter.first.team(Team.crux)
            bystander.first.team(Team.sharded)
            isVoting = true

            val voteData = VoteData(type = VoteType.GameOver, starter = starter.second)
            val vote = VoteSystem(voteData)

            vote.run()

            assertTrue(
                isVoting,
                "a starter alone on their PvP team must not carry a vote on the strength of their own seeded vote alone"
            )
            // The assertion above requires the vote NOT to have passed, so it never reached its own
            // cancel() - do it here rather than leak this instance's chat filter into later tests.
            vote.cancel()
        } finally {
            isVoting = false
            Vars.state.rules.pvp = wasPvp
            listOf(starter, bystander).forEachIndexed { i, p -> p.first.team(startingTeams[i]) }
            listOf(starter, bystander).forEach { leavePlayer(it.first) }
        }
    }

    @Test
    fun aSinglePlayerAloneOnTheWholeServerIsUnaffectedByTheFloor() {
        val starter = newPlayer()
        val wasPvp = Vars.state.rules.pvp
        try {
            Vars.state.rules.pvp = false
            isVoting = true

            val voteData = VoteData(type = VoteType.Skip, wave = 1, starter = starter.second)
            val vote = VoteSystem(voteData)

            vote.run()

            assertTrue(
                !isVoting,
                "the floor must have nothing to do when players.size == 1 - " +
                    "that is the solo case the refused bare '1 -> 2' table entry would have broken"
            )
        } finally {
            isVoting = false
            Vars.state.rules.pvp = wasPvp
            leavePlayer(starter.first)
        }
    }

    @Test
    fun aVotePassHolderStartingTheirOwnVoteCarriesItOnTheFirstTick() {
        val starter = newPlayer()
        // Enough non-afk players that check()'s table alone (without isAdminVote) would demand more
        // than the starter's single seeded vote, so a pass on the first tick proves the instant-pass
        // branch ran rather than the ordinary threshold happening to already be met.
        val bystanders = (1..2).map { newPlayer() }
        try {
            setPermission(starter.first, "admin", true) // grants vote.pass, see permission_default.yaml
            isVoting = true

            val voteData = VoteData(type = VoteType.Skip, wave = 1, starter = starter.second)
            val vote = VoteSystem(voteData)

            val filtered = Vars.netServer.admins.filterMessage(starter.first, "y")
            assertEquals(null, filtered, "the yes/no chat message must still be swallowed by the vote filter")

            vote.run()

            assertTrue(
                !isVoting,
                "task-109: the constructor pre-seeds `voted` with the starter, which used to make the " +
                    "outer guard `!voted.contains(...)` false for them forever - a vote.pass holder's own " +
                    "'y' must carry their vote on the first tick, not wait out the full timer"
            )
        } finally {
            isVoting = false
            (listOf(starter) + bystanders).forEach { leavePlayer(it.first) }
        }
    }
}
