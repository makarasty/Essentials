package essential.core

import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.expectingErrors
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.log
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import arc.Core
import arc.Events
import arc.util.Log
import essential.common.database.data.PlayerData
import essential.common.isVoting
import essential.common.permission.Permission
import essential.common.players
import essential.common.timeSource
import essential.core.service.vote.VoteSystem
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import mindustry.Vars
import mindustry.game.EventType.GameOverEvent
import mindustry.game.EventType.PlayerChatEvent
import mindustry.gen.Player

/** Stands in for BlockIp.BlockPlugin's static voteRefusal, so the reflective lookup has something to find. */
object FakeBlockIp {
    var refused: Set<String> = emptySet()
    var calls = ArrayList<Triple<String, Boolean, Boolean>>()

    @JvmStatic
    fun voteRefusal(uuid: String, starts: Boolean, untrusted: Boolean): String? {
        calls.add(Triple(uuid, starts, untrusted))
        return if (uuid in refused || untrusted) "refused" else null
    }
}

/** One electorate for /rtv and /vote: who may not vote does not count toward what a vote needs. */
class ElectorateTest {
    companion object {
        private var done = false
    }

    private val joined = ArrayList<Pair<Player, PlayerData>>()

    /** The electorate of a server-wide /vote. */
    private val electorate = Electorate(Electorate.VOTE)

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        Rtv.reset()
        FakeBlockIp.calls.clear()
        // Whoever other tests left online is refused, so every count below is of the players this test made
        baseline = players.mapTo(HashSet()) { it.uuid }
        FakeBlockIp.refused = baseline
        useFakeBlockIp()
    }

    private var baseline: Set<String> = emptySet()

    private fun refuse(more: List<Pair<Player, PlayerData>>) {
        FakeBlockIp.refused = baseline + more.map { it.first.uuid() }
    }

    @AfterTest
    fun cleanup() {
        BlockIpHook.replacement = null
        isVoting = false
        Rtv.reset()
        joined.forEach { leavePlayer(it.first) }
        joined.clear()
    }

    private fun join(n: Int) = (1..n).map { newPlayer().also(joined::add) }

    private fun useFakeBlockIp() {
        BlockIpHook.replacement = assertNotNull(BlockIpHook.lookup(FakeBlockIp::class.java.name))
    }

    private fun gameOverDuring(action: () -> Unit): Boolean {
        var over = false
        val listener = arc.func.Cons<GameOverEvent> { over = true }
        Events.on(GameOverEvent::class.java, listener)
        try {
            action()
        } finally {
            Events.remove(GameOverEvent::class.java, listener)
        }
        return over
    }

    @Test
    fun withoutBlockIpOnlyEssentialsRulesApply() {
        assertNull(BlockIpHook.lookup("BlockIp.BlockPlugin"), "BlockIP is not on the test classpath")
        BlockIpHook.replacement = null
        val (a, b) = join(2)
        assertTrue(electorate.mayVote(a.second))
        b.second.afk = true
        assertFalse(electorate.mayVote(b.second), "an afk player may not vote")
        assertTrue(FakeBlockIp.calls.isEmpty())
    }

    @Test
    fun afkPlayersNoLongerBlockAnRtvPass() {
        val voters = join(5)
        // Five online, three of them afk: 60% of the two who may vote is 2, not 3 of 5
        voters.drop(2).forEach { it.second.afk = true }
        assertEquals(2, Rtv.required)

        assertFalse(gameOverDuring { Rtv.vote(voters[0].second) })
        assertTrue(gameOverDuring { Rtv.vote(voters[1].second) }, "the two active players carry /rtv")
    }

    @Test
    fun anIdlePlayerWhoVotesIsPresentAndAVoteStopsCountingOnceItsVoterIsAway() {
        val voters = join(4)
        voters[3].second.afk = true
        assertFalse(electorate.mayVote(voters[3].second), "idle, and no input: away")
        Rtv.vote(voters[3].second)
        assertEquals(1, Rtv.count, "typing /rtv is input: an idle player who votes is at the keyboard")

        voters[3].second.lastInput = null // as if afk.time passed since
        assertEquals(0, Rtv.count, "a vote from a player away again stops counting")
    }

    @Test
    fun chatSeenBeforeTheFiltersIsNotInputAndDoesNotEndAfk() {
        val (a) = join(1)
        a.second.afk = true
        a.second.afkTime = 7u
        Events.fire(PlayerChatEvent(a.first, "hello"))
        assertFalse(electorate.mayVote(a.second), "a line no filter has passed yet proves nothing")
        assertTrue(a.second.afk)
        assertEquals(7u, a.second.afkTime.toUInt(), "chat never holds off afk kicking")
        Trigger.markInput(a.second)
        assertTrue(electorate.mayVote(a.second), "input within afk.time makes an idle player present")
        assertTrue(a.second.afk, "but not active")
    }

    @Test
    fun theTimeoutTickPassesAnRtvOnceTheElectorateShrinks() {
        val voters = join(4)
        Rtv.vote(voters[0].second)
        Rtv.vote(voters[1].second)
        assertEquals(3, Rtv.required)
        voters[2].second.afk = true
        voters[3].second.afk = true
        assertTrue(gameOverDuring { Rtv.tick() }, "two of the two players who may vote is a pass")
    }

    @Test
    fun refusedPlayersOnlineShrinkWhatRtvNeeds() {
        val voters = join(6)
        refuse(voters.drop(2))
        assertEquals(2, Rtv.required, "four newcomers BlockIP refuses do not count")
        Rtv.vote(voters[5].second)
        assertEquals(0, Rtv.count, "a refused player's /rtv is not taken")
        assertFalse(gameOverDuring { Rtv.vote(voters[0].second) })
        assertTrue(gameOverDuring { Rtv.vote(voters[1].second) })
    }

    @Test
    fun inARaidTheTrustedPlayersPassAKickOfARaider() {
        val trusted = join(3)
        val raiders = join(9)
        // Raid mode: BlockIP lets only trusted players vote
        refuse(raiders)
        val target = raiders[0]
        assertEquals(3, electorate.count())

        isVoting = true
        val vote = VoteSystem(VoteData(target = target.first, targetUUID = target.first.uuid(), reason = "raid",
            type = VoteType.Kick, starter = trusted[0].second))
        try {
            raiders.forEach { Vars.netServer.admins.filterMessage(it.first, "y") }
            Vars.netServer.admins.filterMessage(trusted[1].first, "y")
            vote.run()
            assertFalse(isVoting, "two trusted yes votes of three who may vote pass the kick; twelve online would need 8")
        } finally {
            vote.cancel()
        }
    }

    @Test
    fun raidersCannotCarryAVoteTheyAreRefusedIn() {
        val trusted = join(3)
        val raiders = join(9)
        refuse(raiders)

        isVoting = true
        val vote = VoteSystem(VoteData(type = VoteType.Skip, wave = 1, starter = raiders[0].second))
        try {
            raiders.forEach { Vars.netServer.admins.filterMessage(it.first, "y") }
            vote.run()
            assertTrue(isVoting, "refused players' yes votes do not count, the starter's own included")
        } finally {
            vote.cancel()
            isVoting = false
        }
        assertTrue(trusted.all { electorate.mayVote(it.second) })
    }

    @Test
    fun aHeldBackSessionIsPassedToBlockIpAsUntrusted() {
        val (normal, held) = join(2)
        val uuid = held.first.uuid()
        try {
            Permission.bindDevice(uuid, "another-device")
            assertFalse(electorate.mayVote(held.second), "the hook refuses the untrusted session")
            assertEquals(Triple(uuid, false, true), FakeBlockIp.calls.last())
            assertTrue(electorate.mayVote(normal.second, starts = true))
            assertEquals(Triple(normal.first.uuid(), true, false), FakeBlockIp.calls.last())
        } finally {
            Core.settings.remove("essential-device-$uuid")
        }
    }

    /** IDs of two maps that are not the one being played. */
    private fun twoOtherMaps(): Pair<Int, Int> {
        val ids = mapsById().indices.filter { !isCurrentMap(mapsById()[it]) }
        assertTrue(ids.size >= 2, "the test server has at least two maps besides the current one")
        return ids[0] to ids[1]
    }

    @Test
    fun rtvWithAnIdVotesForThatMapAndAPassPlaysIt() {
        val (id, other) = twoOtherMaps()
        val map = mapsById()[id]
        val voters = join(4)
        val (a, b, c) = voters
        val name = a.first.plainName()

        Rtv.vote(a.second, "$id")
        assertEquals(log("command.rtv.voted.map", name, 1, 3, map.plainName()), a.second.lastReceivedMessage)

        Rtv.vote(b.second, "$other")
        assertEquals(err("command.rtv.other.map", map.plainName()), b.second.lastReceivedMessage, "another id is refused")
        assertEquals(1, Rtv.count)

        Rtv.vote(b.second, "$id")
        assertEquals(2, Rtv.count, "the same id joins the running vote")
        mapVotes[c.first.uuid()] = mapsById()[other] // a /nextmap round, which the vote's map replaces
        assertTrue(gameOverDuring { Rtv.vote(c.second) }, "no id joins it too")
        assertEquals(map.file, Vars.maps.getNextMap(Vars.state.rules.mode(), Vars.state.map)?.file, "the vote's map is played next")
        assertTrue(mapVotes.isEmpty(), "and the /nextmap tally is not announced over it")
    }

    @Test
    fun rtvIsCancelledWhenItsMapLeavesTheServer() {
        val (id, _) = twoOtherMaps()
        val map = mapsById()[id]
        val (a, b, _) = join(3)
        Rtv.vote(a.second, "$id")
        Vars.maps.all().remove(map)
        try {
            assertFalse(gameOverDuring { Rtv.vote(b.second) })
            assertEquals(log("command.rtv.map.gone", map.plainName()), b.second.lastReceivedMessage)
            assertEquals(0, Rtv.count)
            Rtv.vote(a.second)
            assertEquals(1, Rtv.count, "a vote that did nothing leaves no cooldown behind")
        } finally {
            Vars.maps.all().add(map)
        }
    }

    @Test
    fun aVoteForTheMapBeingPlayedIsRefused() {
        val (id, _) = twoOtherMaps()
        val map = mapsById()[id]
        val (a) = join(1)
        val playing = Vars.state.map
        Vars.state.map = map
        try {
            Rtv.vote(a.second, "$id")
            assertEquals(err("command.vote.map.current", map.plainName()), a.second.lastReceivedMessage)
            assertEquals(0, Rtv.count)
        } finally {
            Vars.state.map = playing
        }
    }

    @Test
    fun aLonePlayerChangesTheMapWithVoteMapEvenWhenTheyMayNotVote() {
        players.toList().forEach { leavePlayer(it.player) }
        val (id, _) = twoOtherMaps()
        val (a) = join(1)
        setPermission(a.first, "owner", false)
        refuse(listOf(a))
        clientCommand.handleMessage("/vote map $id alone", a.first)
        assertEquals(mapsById()[id].file, Vars.state.map.file, "alone, vote map is a direct change, not a vote")
    }

    @Test
    fun aLonePlayerMayRestartTheMapBeingPlayedButAVoteForItIsRefused() {
        players.toList().forEach { leavePlayer(it.player) }
        val (id, _) = twoOtherMaps()
        val map = mapsById()[id]
        val (a) = join(1)
        setPermission(a.first, "owner", false)
        Vars.state.map = map
        a.second.lastReceivedMessage = ""
        clientCommand.handleMessage("/vote map $id restart", a.first)
        assertTrue(a.second.lastReceivedMessage != err("command.vote.map.current", map.plainName()), "alone it is a restart")
        assertEquals(map.file, Vars.state.map.file)

        join(1)
        clientCommand.handleMessage("/vote map $id again", a.first)
        assertEquals(err("command.vote.map.current", map.plainName()), a.second.lastReceivedMessage, "as a vote it is refused")
        assertFalse(isVoting)
    }

    @Test
    fun findMapReadsANumberAsAnIdOnlyAndAnythingElseAsANameIgnoringCase() {
        val maps = mapsById()
        assertEquals(maps[0], findMap("0"))
        assertNull(findMap("${maps.size}"), "past the last ID there is no map, whatever the names hold")
        assertEquals(maps.first { it.plainName().contains(maps[1].plainName().uppercase(), ignoreCase = true) },
            findMap(maps[1].plainName().uppercase()))
    }

    @Test
    fun aChatLinePastEveryFilterIsInputWithoutTheChatModule() {
        val (a) = join(1)
        a.second.afk = true
        // The chat module's formatter takes no part: it is the core's own filter, at the end of the chain
        Vars.netServer.chatFormatter.format(a.first, "hello")
        assertFalse(electorate.mayVote(a.second), "the formatter is the chat module's and marks nothing")
        Vars.netServer.admins.filterMessage(a.first, "hello")
        assertTrue(electorate.mayVote(a.second), "a line the filters let through is input")
    }

    @Test
    fun voteMapIsRefusedToAPlayerWhoMayNotVoteOnceAnotherPlayerIsOn() {
        val (id, _) = twoOtherMaps()
        val (a, _) = join(2)
        setPermission(a.first, "owner", false)
        refuse(listOf(a))
        val playing = Vars.state.map.file
        clientCommand.handleMessage("/vote map $id together", a.first)
        assertEquals(err("command.vote.ineligible"), a.second.lastReceivedMessage)
        assertEquals(playing, Vars.state.map.file)
        assertFalse(isVoting)
    }

    @Test
    fun rtvRefusesAnIdThatIsNoMap() {
        val (a) = join(1)
        Rtv.vote(a.second, "9999")
        assertEquals(err("command.rtv.map.invalid", "9999"), a.second.lastReceivedMessage)
        Rtv.vote(a.second, "no-such-map-name")
        assertEquals(err("command.rtv.map.invalid", "no-such-map-name"), a.second.lastReceivedMessage)
        assertEquals(0, Rtv.count)
    }

    @Test
    fun rtvWithoutAnIdIsForTheNextMapAndRefusesAnIdWhileItRuns() {
        val (id, _) = twoOtherMaps()
        val (a, b, _) = join(3)
        Rtv.vote(a.second)
        assertEquals(log("command.rtv.voted", a.first.plainName(), 1, 2), a.second.lastReceivedMessage)
        Rtv.vote(b.second, "$id")
        assertEquals(err("command.rtv.other.next"), b.second.lastReceivedMessage)
        assertEquals(1, Rtv.count)
    }

    @Test
    fun eachVoteAsksForItsOwnNode() {
        val (a) = join(1)
        assertTrue(Electorate(Electorate.RTV).mayVote(a.second))
        assertFalse(Electorate("no.such.node").mayVote(a.second), "the node is the vote's own, not a fixed one")
    }

    @Test
    fun aFailingHookFallsBackToEssentialsRulesAndLogsAtMostOnceAMinute() {
        val (a) = join(1)
        val clock = TestTimeSource()
        BlockIpHook.clock = clock
        BlockIpHook.replacement = { _, _, _ -> throw IllegalStateException("broken") }
        val logged = AtomicInteger()
        val previous = Log.logger
        Log.logger = Log.LogHandler { level, text ->
            if (text.contains("BlockIP's vote rule failed")) logged.incrementAndGet()
            previous.log(level, text)
        }
        try {
            expectingErrors("BlockIP's vote rule failed") {
                assertTrue(electorate.mayVote(a.second), "a failing hook allows")
                assertTrue(electorate.mayVote(a.second))
                assertEquals(1, logged.get(), "logged once")
                clock += ElectorateTuning.HOOK_FAILURE_LOG_MINUTES.minutes
                electorate.mayVote(a.second)
                assertEquals(2, logged.get(), "and again a minute later")
            }
        } finally {
            Log.logger = previous
            BlockIpHook.clock = timeSource
        }
    }
}
