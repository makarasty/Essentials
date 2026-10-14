package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import arc.Core
import arc.Events
import essential.common.database.data.PlayerData
import essential.common.isVoting
import essential.common.permission.Permission
import essential.common.players
import essential.core.service.vote.VoteSystem
import mindustry.Vars
import mindustry.game.EventType.GameOverEvent
import mindustry.gen.Player
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        Electorate.external = null
        isVoting = false
        Rtv.reset()
        joined.forEach { leavePlayer(it.first) }
        joined.clear()
    }

    private fun join(n: Int) = (1..n).map { newPlayer().also(joined::add) }

    private fun useFakeBlockIp() {
        Electorate.external = assertNotNull(Electorate.lookup(FakeBlockIp::class.java.name))
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
        assertNull(Electorate.lookup("BlockIp.BlockPlugin"), "BlockIP is not on the test classpath")
        Electorate.external = null
        val (a, b) = join(2)
        assertTrue(Electorate.mayVote(a.second))
        b.second.afk = true
        assertFalse(Electorate.mayVote(b.second), "an afk player may not vote")
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
    fun anAfkPlayersRtvIsRefusedAndAVoteStopsCountingOnceItsVoterGoesAfk() {
        val voters = join(4)
        voters[3].second.afk = true
        Rtv.vote(voters[3].second)
        assertEquals(0, Rtv.count, "an afk /rtv is refused")

        Rtv.vote(voters[0].second)
        assertEquals(1, Rtv.count)
        voters[0].second.afk = true
        assertEquals(0, Rtv.count, "a vote from a player gone afk stops counting")
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
        assertEquals(3, Electorate.count())

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
        assertTrue(trusted.all { Electorate.mayVote(it.second) })
    }

    @Test
    fun aHeldBackSessionIsPassedToBlockIpAsUntrusted() {
        val (normal, held) = join(2)
        val uuid = held.first.uuid()
        try {
            Permission.bindDevice(uuid, "another-device")
            assertFalse(Electorate.mayVote(held.second), "the hook refuses the untrusted session")
            assertEquals(Triple(uuid, false, true), FakeBlockIp.calls.last())
            assertTrue(Electorate.mayVote(normal.second, starts = true))
            assertEquals(Triple(normal.first.uuid(), true, false), FakeBlockIp.calls.last())
        } finally {
            Core.settings.remove("essential-device-$uuid")
        }
    }
}
