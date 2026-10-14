package essential.core

import arc.util.Log
import essential.common.database.data.PlayerData
import essential.common.permission.Permission
import essential.common.players
import mindustry.Vars
import mindustry.game.Team

/**
 * Who may vote, in every vote this plugin runs: /vote (its kick too) and /rtv. One rule for casting and for
 * counting, so a player who may not vote never counts toward what a vote needs. Before this, players who
 * could not answer a vote - afk, a team's spectators, newcomers and a raid's bots that BlockIP refuses -
 * still raised its threshold, so /rtv and a kick of raiders could not pass while they were online.
 *
 * The permission is the `vote` node, for /rtv too. It is the node the default groups hold together with
 * `rtv` and every `vote.<type>`, and the one a group without the right to take part in votes lacks, so it
 * reads as membership of the electorate; the per-type nodes only gate who may start each kind of vote.
 */
object Electorate {
    /** BlockIP's vote rule (see [lookup]); null when BlockIP is not installed. */
    private const val BLOCKIP_CLASS = "BlockIp.BlockPlugin"

    /**
     * BlockIP's answer for (uuid, starts, untrusted): why this player may not vote, or null. Looked up once,
     * the first time anyone votes, which is after every plugin is loaded. Tests replace it.
     */
    @Volatile
    internal var external: ((String, Boolean, Boolean) -> String?)? = lookup(BLOCKIP_CLASS)

    private var hookFailed = false

    /**
     * Whether [data] may vote now. [team]: the vote's team on a PvP map, null for one the whole server votes in.
     * [starts]: the vote is a kick this player starts, which BlockIP holds to more active minutes than a cast.
     */
    fun mayVote(data: PlayerData, team: Team? = null, starts: Boolean = false): Boolean {
        val player = data.player
        val con = player.con()
        return !data.temporary && !data.afk
            && con != null && !con.hasDisconnected
            && player.team() != Team.derelict && data.uuid !in pvpSpecters
            && (team == null || player.team() == team)
            && Permission.check(data, "vote")
            && externalAllows(data, starts)
    }

    /** How many players online may vote now, on [team] when it is given. */
    fun count(team: Team? = null): Int = players.count { mayVote(it, team) }

    private fun externalAllows(data: PlayerData, starts: Boolean): Boolean {
        val hook = external ?: return true
        // A session on a device the uuid is not bound to may be someone presenting another player's uuid:
        // BlockIP is told so, and does not lend it that uuid's trust.
        val untrusted = Permission.heldBack(data.uuid, Permission.sessionUsid(data.player))
        return try {
            hook(data.uuid, starts, untrusted) == null
        } catch (e: Throwable) {
            // Fails open, as if BlockIP were absent: Essentials' own rules above still apply.
            if (!hookFailed) Log.err("[Essential] BlockIP's vote rule failed; votes follow Essentials' rules only", e)
            hookFailed = true
            true
        }
    }

    /** The static `voteRefusal(String, boolean, boolean)` of [className], or null when it is not there. */
    internal fun lookup(className: String): ((String, Boolean, Boolean) -> String?)? = try {
        val loader = Vars.mods?.mainLoader() ?: Electorate::class.java.classLoader
        val method = Class.forName(className, true, loader).getMethod(
            "voteRefusal", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        )
        Log.info("[Essential] BlockIP found: its vote rule applies to every vote")
        val hook: (String, Boolean, Boolean) -> String? = { uuid, starts, untrusted ->
            method.invoke(null, uuid, starts, untrusted) as String?
        }
        hook
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: NoSuchMethodException) {
        // An older BlockIP: it still drops refused players' vote words and commands itself.
        null
    } catch (_: LinkageError) {
        null
    }
}
