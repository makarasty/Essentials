package essential.core

import arc.Events
import arc.util.Timer
import essential.common.database.data.PlayerData
import essential.common.isSurrender
import essential.common.players
import essential.common.timeSource
import essential.core.Main.Companion.conf
import mindustry.Vars
import mindustry.game.EventType.GameOverEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

object Rtv {
    private val votes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cooldown = ConcurrentHashMap<String, TimeMark>()
    private var timeout: Timer.Task? = null
    private var deadline: TimeMark? = null
    private var changing = false

    /** Votes that count: from players who may still vote (see [Electorate]). Ones gone afk count again once back. */
    val count: Int get() = votes.count { uuid -> players.byUuid(uuid)?.let { Electorate.mayVote(it) } == true }

    /** Sized from the players who may vote now, late joiners included: anyone who may vote, may vote here. */
    val required: Int get() = requiredFor(Electorate.count())

    fun vote(playerData: PlayerData) {
        val settings = conf.feature.vote.rtv
        val uuid = playerData.uuid

        if (changing) {
            playerData.err("command.rtv.changing")
            return
        }
        if (!Electorate.mayVote(playerData)) {
            playerData.err("command.vote.ineligible")
            return
        }
        val wait = cooldown[uuid]
        if (wait != null && !wait.hasPassedNow()) {
            playerData.err("command.rtv.cooldown")
            return
        }
        cooldown[uuid] = timeSource.markNow().plus(settings.cooldown.seconds)

        if (!votes.add(uuid)) {
            playerData.err("command.rtv.already", count, required)
            return
        }
        if (votes.size == 1) startTimeout(settings.timeout)

        if (required == 1) {
            broadcast("command.rtv.alone", playerData.player.plainName())
        } else {
            broadcast("command.rtv.voted", playerData.player.plainName(), count, required)
        }
        if (count >= required) change()
    }

    fun leave(uuid: String, name: String) {
        val voted = votes.remove(uuid)
        if (votes.isEmpty()) {
            clearVotes()
            return
        }
        // The leaver is still in players while PlayerLeave runs
        val needed = requiredFor(players.count { it.uuid != uuid && Electorate.mayVote(it) })
        if (voted) broadcast("command.rtv.left", name, count, needed)
        if (count >= needed) change()
    }

    fun reset() {
        clearVotes()
        cooldown.clear()
        changing = false
    }

    private fun requiredFor(playerCount: Int): Int =
        ceil(conf.feature.vote.rtv.ratio * playerCount).toInt().coerceAtLeast(1)

    private fun change() {
        clearVotes()
        changing = true
        broadcast("command.rtv.done")
        isSurrender = true
        Events.fire(GameOverEvent(Vars.state.rules.waveTeam))
    }

    /**
     * Rechecks the vote every [RtvTuning.RECHECK_SECONDS] until [seconds] run out: players who go afk or leave
     * the electorate lower what it needs, and nobody else may be about to vote again to notice.
     */
    private fun startTimeout(seconds: Int) {
        timeout?.cancel()
        deadline = timeSource.markNow().plus(seconds.seconds)
        timeout = Timer.schedule(::tick, RtvTuning.RECHECK_SECONDS, RtvTuning.RECHECK_SECONDS)
    }

    internal fun tick() {
        when {
            votes.isEmpty() || changing -> clearVotes()
            count >= required -> change()
            deadline?.hasPassedNow() != false -> {
                broadcast("command.rtv.timeout")
                clearVotes()
            }
        }
    }

    private fun clearVotes() {
        timeout?.cancel()
        timeout = null
        deadline = null
        votes.clear()
    }

    private fun broadcast(key: String, vararg args: Any) {
        players.forEach { it.send(key, *args) }
    }
}
