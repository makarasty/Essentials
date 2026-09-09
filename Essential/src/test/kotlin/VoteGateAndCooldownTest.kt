import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import essential.common.isVoting
import essential.common.nextVoteAvailable
import essential.common.timeSource
import mindustry.Vars
import mindustry.game.Team
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Group B of the backlog, ruled in answers/9-2.md: the vote start gate and VoteSystem.check()'s pass
 * threshold have never counted the same electorate, and kick/map/back were exempt from the cooldown
 * every other vote type obeys.
 */
class VoteGateAndCooldownTest {
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
    fun theStartGateCountsTheStartersTeamOnAPvpMapRatherThanBystandersElsewhere() {
        val starter = newPlayer()
        setPermission(starter.first, "user", false)
        // Four bystanders on the default team keep the server-wide non-afk count comfortably above 3,
        // even though the starter is effectively alone on their own PvP team.
        val bystanders = (1..4).map { newPlayer() }

        val wasPvp = Vars.state.rules.pvp
        val startingTeams = (listOf(starter) + bystanders).map { it.first.team() }
        try {
            Vars.state.rules.pvp = true
            starter.first.team(Team.crux)

            starter.second.lastReceivedMessage = ""
            clientCommand.handleMessage("/vote gg", starter.first)

            assertEquals(
                err("command.vote.enough"),
                starter.second.lastReceivedMessage,
                "a starter alone on their own PvP team must not be let through by counting bystanders on another team"
            )
        } finally {
            Vars.state.rules.pvp = wasPvp
            (listOf(starter) + bystanders).forEachIndexed { i, p -> p.first.team(startingTeams[i]) }
            (listOf(starter) + bystanders).forEach { leavePlayer(it.first) }
        }
    }

    @Test
    fun kickObeysTheSameVoteCooldownAsGgAndSkip() {
        val starter = newPlayer()
        setPermission(starter.first, "owner", true)
        val target = newPlayer()

        val previous = nextVoteAvailable
        try {
            nextVoteAvailable = timeSource.markNow().plus(3.minutes)
            starter.second.lastReceivedMessage = ""
            clientCommand.handleMessage("/vote kick ${target.first.uuid()} testing", starter.first)

            assertEquals(
                err("command.vote.coolTime"),
                starter.second.lastReceivedMessage,
                "kick used to be exempt from the cooldown gg/skip/random/draw all obey"
            )
        } finally {
            nextVoteAvailable = previous
            leavePlayer(starter.first)
            leavePlayer(target.first)
        }
    }
}
