package essential.core

import arc.util.Log
import essential.common.database.data.PlayerData
import essential.common.permission.Permission
import essential.common.players
import essential.common.timeSource
import mindustry.Vars
import mindustry.game.Team
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Who may vote in one vote, and so who counts toward what it needs: the single rule for /rtv, /vote and its kick,
 * and the gate that starts a /vote. Casting, the tally and the threshold all ask [mayVote], so a player who may
 * not vote can neither carry a vote nor block it. Before this, players who could not answer - afk, a team's
 * spectators, newcomers and a raid's bots that BlockIP refuses - still raised every threshold, so /rtv and a
 * kick of raiders could not pass while they were online.
 *
 * A player may vote when they are on the server with their data loaded (not temporary), present (Trigger.present:
 * not afk, or gave chat or vote input within `afk.time` seconds), playing (not derelict, not a PvP spectator), on [team] when the vote is a team's, one of [members]
 * when the vote fixed them at its start, holding [node] - the vote's own command node, `rtv` or `vote` - and
 * BlockIP, when installed, lets them (see [BlockIpHook]).
 */
class Electorate(
    private val node: String,
    private val team: Team? = null,
    private val members: Set<String>? = null,
) {
    /** Whether [data] may vote now. [starts]: the vote is a kick they start, which BlockIP holds to more minutes. */
    fun mayVote(data: PlayerData, starts: Boolean = false): Boolean {
        val player = data.player
        val con = player.con()
        return !data.temporary && Trigger.present(data)
            && con != null && !con.hasDisconnected
            && player.team() != Team.derelict && data.uuid !in pvpSpecters
            && (team == null || player.team() == team)
            && (members == null || data.uuid in members)
            && Permission.check(data, node)
            && BlockIpHook.allows(data, starts)
    }

    /**
     * [data] expresses a vote - a vote command or a vote word, the one path for both: that is input (see
     * Trigger.markInput), so an idle player who votes is at the keyboard; then whether they may vote ([mayVote]).
     */
    fun castBy(data: PlayerData, starts: Boolean = false): Boolean {
        Trigger.markInput(data)
        return mayVote(data, starts)
    }

    /** How many players online may vote now, leaving out [except] (a player who is leaving). */
    fun count(except: String? = null): Int = players.count { it.uuid != except && mayVote(it) }

    /** How many of [voters] (uuids) may vote now: the votes that count. One gone afk counts again once back. */
    fun tally(voters: Iterable<String>): Int = voters.count { uuid -> players.byUuid(uuid)?.let { mayVote(it) } == true }

    companion object {
        /** The node of /rtv. */
        const val RTV = "rtv"

        /** The node of /vote, its kick included. */
        const val VOTE = "vote"

        /**
         * A /vote's electorate: [team]'s players on a PvP map, the whole server otherwise. [fixMembers]: only the
         * players online now may ever vote in it - a running vote, which bots joining later must not answer.
         */
        fun forVote(team: Team, fixMembers: Boolean = false): Electorate {
            val scope = team.takeIf { Vars.state.rules.pvp }
            val members = if (fixMembers) players.filter { scope == null || it.player.team() == scope }.mapTo(HashSet()) { it.uuid } else null
            return Electorate(VOTE, scope, members)
        }
    }
}

/**
 * BlockIP's vote rule, when BlockIP is installed: its public static `BlockIp.BlockPlugin.voteRefusal(uuid, starts,
 * untrusted)`, null for "may vote". Found by reflection, so Essentials neither needs BlockIP nor breaks without it.
 */
internal object BlockIpHook {
    private const val CLASS = "BlockIp.BlockPlugin"

    /** Looked up at the first vote; Mindustry loads every plugin before any init, so later is no different. */
    private val found by lazy { lookup(CLASS) }

    /** Stands in for BlockIP in tests; null uses the real lookup. */
    internal var replacement: ((String, Boolean, Boolean) -> String?)? = null

    private var nextFailureLog: TimeMark? = null

    /** The clock the failure log is spaced by; tests replace it, which starts the spacing over. */
    internal var clock: TimeSource = timeSource
        set(value) {
            field = value
            nextFailureLog = null
        }

    /**
     * Whether BlockIP lets [data] vote: true without BlockIP, and when the call fails - Essentials' own rules still
     * apply then, and the failure is logged at most every [ElectorateTuning.HOOK_FAILURE_LOG_MINUTES].
     */
    fun allows(data: PlayerData, starts: Boolean): Boolean {
        val hook = replacement ?: found ?: return true
        // A session on a device the uuid is not bound to may be someone presenting another player's uuid:
        // BlockIP is told so, and does not lend it that uuid's trust.
        val untrusted = Permission.heldBack(data.uuid, Permission.sessionUsid(data.player))
        return try {
            hook(data.uuid, starts, untrusted) == null
        } catch (e: Throwable) {
            if (nextFailureLog?.hasPassedNow() != false) {
                Log.err("[Essential] BlockIP's vote rule failed; votes follow Essentials' rules only", e)
                nextFailureLog = clock.markNow().plus(ElectorateTuning.HOOK_FAILURE_LOG_MINUTES.minutes)
            }
            true
        }
    }

    /** The static `voteRefusal(String, boolean, boolean)` of [className], or null when it cannot be had. */
    internal fun lookup(className: String): ((String, Boolean, Boolean) -> String?)? = try {
        val loader = Vars.mods?.mainLoader() ?: BlockIpHook::class.java.classLoader
        val method = Class.forName(className, true, loader).getMethod(
            "voteRefusal", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        )
        Log.info("[Essential] BlockIP found: its vote rule applies to every vote")
        val hook: (String, Boolean, Boolean) -> String? = { uuid, starts, untrusted ->
            method.invoke(null, uuid, starts, untrusted) as String?
        }
        hook
    } catch (_: Throwable) {
        // Absent, or an older BlockIP without the method: it still drops refused players' vote words itself.
        null
    }
}
