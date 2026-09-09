import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.loadGame
import PluginTest.Companion.log
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.data.getPlayerData
import essential.common.isVoting
import essential.common.systemTimezone
import essential.core.TempBan
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import mindustry.Vars
import mindustry.game.Team
import mindustry.net.Administration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Four command-layer defects that share a shape: a command reaching past the thing that was supposed to
 * check it. Each test drives the real command and asserts the observable the defect changed, and each is
 * paired with the control that keeps it from passing for an unrelated reason.
 */
@OptIn(ExperimentalTime::class)
class CommandSafetyTest {
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
    fun expOnAnotherPlayerWritesThatPlayersRow() {
        val admin = newPlayer()
        val target = newPlayer()
        setPermission(admin.first, "owner", true)

        val adminBefore = admin.second.exp
        val targetBefore = target.second.exp

        clientCommand.handleMessage("/exp add 17 ${target.first.uuid()}", admin.first)

        // Only the target's row is asserted. The obvious companion - that the caller's row is unchanged -
        // cannot fail: update() writes only columns that differ from its snapshot, so the caller's exp
        // reads the same either way. The wrong row being written was never the visible symptom; the
        // right row not being written was.
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(target.first.uuid())?.exp } == targetBefore + 17 },
            "exp add on another player must persist to that player's row"
        )
    }

    @Test
    fun teamChatGoesThroughTheChatFilters() {
        val sender = newPlayer()
        // owner because no group in the shipped permission_default.yaml grants `t`; which group has it
        // is the operator's decision and not what this test is about.
        setPermission(sender.first, "owner", true)

        // Everything the command was skipping is an Administration.ChatFilter, and filterMessage is the
        // only thing that runs one, so the assertion is that the chain ran at all rather than that any
        // particular filter fired - which would tie the test to the server's mute state or its blacklist
        // file. This observer registers last, so it sees the message only if every earlier filter passed
        // it; that is what makes reaching it meaningful. Chat filters cannot be unregistered, so it only
        // observes and returns what it was given.
        //
        // The sender is an admin, which exempts them from the engine's anti-spam filter. That keeps a
        // repeated run of this test from tripping the duplicate-message check, and it is the reason this
        // test cannot also be used to observe that filter.
        val seen = AtomicReference<String?>(null)
        Vars.netServer.admins.addChatFilter(Administration.ChatFilter { _, message ->
            seen.set(message)
            message
        })
        assertNull(seen.get(), "nothing should have been filtered before the command runs")

        clientCommand.handleMessage("/t hello there", sender.first)

        assertTrue(
            waitUntil(5000) { seen.get() == "hello there" },
            "team chat must go through the chat filters, saw: '${seen.get()}'"
        )
    }

    @Test
    fun aPvpKickVoteIsPolledInTheStartersTeam() {
        val starter = newPlayer()
        val target = newPlayer()
        val bystander = newPlayer()
        setPermission(starter.first, "owner", true)

        val wasPvp = Vars.state.rules.pvp
        val teams = listOf(starter, target, bystander).map { it.first.team() }
        try {
            Vars.state.rules.pvp = true
            starter.first.team(Team.crux)
            target.first.team(Team.crux)
            bystander.first.team(Vars.state.rules.defaultTeam)
            assertNotEquals(
                Vars.state.rules.defaultTeam,
                starter.first.team(),
                "the starter has to be off the default team or this test passes without the fix"
            )

            starter.second.lastReceivedMessage = ""
            bystander.second.lastReceivedMessage = ""
            clientCommand.handleMessage("/vote kick ${target.first.uuid()} testing", starter.first)

            // Who the poll is sent to is voteData.team, so the assertion is a pair: the starter's team
            // hears about the vote and the team the starter is not on does not. Without the fix the
            // team is the default one and both halves are the wrong way round.
            //
            // The expected text matters. Every early return in the command - a vote already running, a
            // cooldown, a permission miss - also writes lastReceivedMessage, so asserting merely that
            // something arrived would go green on any of them. Dispatch is synchronous and the poll is
            // sent from the VoteSystem constructor, so this is read without pumping: the vote's own
            // one-second countdown would otherwise overwrite it.
            assertEquals(
                log("command.vote.how"),
                starter.second.lastReceivedMessage,
                "a pvp kick vote is polled within the starter's team, so the starter is told how to vote"
            )
            assertEquals(
                "",
                bystander.second.lastReceivedMessage,
                "a pvp kick vote must not be polled outside the starter's team"
            )
        } finally {
            // Directly, not through /vote reset: that flips the same flag but leaves the scheduled task
            // and its chat filter registered, and a stale filter swallows the next vote's answers.
            isVoting = false
            Vars.state.rules.pvp = wasPvp
            listOf(starter, target, bystander).forEachIndexed { i, p -> p.first.team(teams[i]) }
        }
    }

    /**
     * The other half of scoping the poll. Assigning `voteData.team` decides who votes; without this it
     * would also mean a team can ban a player who never saw the vote, and a player alone on a team can
     * do it unopposed, because the threshold counts only that team.
     */
    @Test
    fun aPvpKickVoteRefusesATargetOnAnotherTeam() {
        val starter = newPlayer()
        val target = newPlayer()
        setPermission(starter.first, "owner", true)

        val wasPvp = Vars.state.rules.pvp
        val teams = listOf(starter, target).map { it.first.team() }
        try {
            Vars.state.rules.pvp = true
            starter.first.team(Team.crux)
            target.first.team(Vars.state.rules.defaultTeam)

            starter.second.lastReceivedMessage = ""
            clientCommand.handleMessage("/vote kick ${target.first.uuid()} testing", starter.first)

            assertEquals(
                err("command.vote.kick.target.admin", target.first.plainName()),
                starter.second.lastReceivedMessage,
                "a pvp kick vote across teams is decided by a team the target is not on"
            )
        } finally {
            isVoting = false
            Vars.state.rules.pvp = wasPvp
            listOf(starter, target).forEachIndexed { i, p -> p.first.team(teams[i]) }
        }
    }

    /**
     * The window is a few milliseconds wide on a live server, so it is driven here rather than waited
     * for: tick() chooses the batch and posts the unban, the permaban lands, and only then is the posted
     * runnable allowed to run.
     *
     * The control is the first half of this same test, on this same uuid, deliberately. As a separate
     * test on a separate player it proved only that the sweep works on *that* row: if tick() returned
     * early on this one - an invisible write, an empty ban list - nothing would be posted and the
     * assertion below would go green having tested nothing.
     */
    @Test
    fun aPermabanBetweenTheSweepAndItsUnbanKeepsTheBan() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val admins = Vars.netServer.admins
        fun expired() = Clock.System.now().minus(1.minutes).toLocalDateTime(systemTimezone)
        try {
            admins.banPlayerID(uuid)
            runBlocking {
                TempBan.setBanExpire(uuid, expired())
                TempBan.tick()
            }
            pumpApp()
            assertFalse(admins.isIDBanned(uuid), "an expired ban with nothing intervening is still lifted")

            admins.banPlayerID(uuid)
            runBlocking {
                TempBan.setBanExpire(uuid, expired())
                TempBan.tick()
                TempBan.clearBanExpire(uuid)
            }
            pumpApp()

            assertTrue(admins.isIDBanned(uuid), "a ban made permanent inside the sweep's window must survive it")
            assertNull(
                runBlocking { getPlayerData(uuid)?.banExpireDate },
                "and the permaban must have landed, or the next sweep thirty seconds later lifts the ban anyway"
            )
        } finally {
            // The expiry is cleared as well as the ban: a row left with a past expiry stays in every
            // later tick()'s select for the life of the JVM, and this suite shares one.
            runBlocking { TempBan.clearBanExpire(uuid) }
            admins.unbanPlayerID(uuid)
            pumpApp()
        }
    }
}
