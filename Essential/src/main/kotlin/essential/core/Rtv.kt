package essential.core

import arc.files.Fi
import arc.util.Timer
import essential.common.database.data.PlayerData
import essential.common.players
import essential.common.timeSource
import essential.core.Main.Companion.conf
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

object Rtv {
    private val votes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val cooldown = ConcurrentHashMap<String, TimeMark>()
    private var timeout: Timer.Task? = null
    private var deadline: TimeMark? = null

    /** The map the running vote is for, by file (see [mapByFile]) and with the name it was shown by; null for the next map. */
    private class Target(val file: Fi, val name: String)

    private var target: Target? = null
    private var changing = false

    /** Every player online who may use /rtv: late joiners vote and count like anyone else. */
    private val electorate = Electorate(Electorate.RTV)

    /** Votes that count: from players who may still vote. */
    val count: Int get() = electorate.tally(votes)

    /** [ratio][RockTheVote.ratio] of the players who may vote now, one at least. */
    val required: Int get() = requiredFor(electorate.count())

    /**
     * A vote to move on: to the next map, or with [id] (an ID /maps shows, or a name, see [findMap]) to that map.
     * The first vote sets the target; later ones without an id, or with the same map, join it, and another map is
     * refused until it ends.
     */
    fun vote(playerData: PlayerData, id: String? = null) {
        val settings = conf.feature.vote.rtv
        val uuid = playerData.uuid

        if (changing) {
            playerData.err("command.rtv.changing")
            return
        }
        val map = id?.let(::findMap)
        if (id != null && map == null) {
            playerData.err("command.rtv.map.invalid", id)
            return
        }
        if (map != null && isCurrentMap(map)) {
            playerData.err("command.vote.map.current", map.plainName())
            return
        }
        if (!electorate.castBy(playerData)) {
            playerData.err("command.vote.ineligible")
            return
        }
        val wait = cooldown[uuid]
        if (wait != null && !wait.hasPassedNow()) {
            playerData.err("command.rtv.cooldown")
            return
        }
        val running = votes.isNotEmpty()
        val current = target
        if (running && map != null && map.file != current?.file) {
            if (current == null) playerData.err("command.rtv.other.next") else playerData.err("command.rtv.other.map", current.name)
            return
        }
        cooldown[uuid] = timeSource.markNow().plus(settings.cooldown.seconds)

        if (!votes.add(uuid)) {
            playerData.err("command.rtv.already", count, required)
            return
        }
        if (!running) {
            target = map?.let { Target(it.file, it.plainName()) }
            startTimeout(settings.timeout)
        }

        val name = playerData.player.plainName()
        val to = target?.name
        when {
            required == 1 && to == null -> broadcast("command.rtv.alone", name)
            required == 1 -> broadcast("command.rtv.alone.map", name, to!!)
            to == null -> broadcast("command.rtv.voted", name, count, required)
            else -> broadcast("command.rtv.voted.map", name, count, required, to)
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
        val needed = requiredFor(electorate.count(except = uuid))
        val to = target?.name
        if (voted) {
            if (to == null) broadcast("command.rtv.left", name, count, needed) else broadcast("command.rtv.left.map", name, count, needed, to)
        }
        if (count >= needed) change()
    }

    fun reset() {
        clearVotes()
        cooldown.clear()
        changing = false
    }

    private fun requiredFor(playerCount: Int): Int =
        ceil(conf.feature.vote.rtv.ratio * playerCount).toInt().coerceAtLeast(1)

    /** Passes the vote: on to its map, found again by file, or cancelled when that map has left the server meanwhile. */
    private fun change() {
        val wanted = target
        val map = wanted?.let { mapByFile(it.file) }
        if (wanted != null && map == null) {
            // Nobody's /rtv did anything, so nobody waits out a cooldown for it
            votes.forEach(cooldown::remove)
            clearVotes()
            broadcast("command.rtv.map.gone", wanted.name)
            return
        }
        clearVotes()
        changing = true
        broadcast("command.rtv.done")
        endMap(map)
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
        target = null
        votes.clear()
    }

    private fun broadcast(key: String, vararg args: Any) {
        players.forEach { it.send(key, *args) }
    }
}
