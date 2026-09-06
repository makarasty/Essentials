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
    private var changing = false

    val count: Int get() = votes.size

    val required: Int get() = requiredFor(players.size)

    fun vote(playerData: PlayerData) {
        val settings = conf.feature.vote.rtv
        val uuid = playerData.uuid

        if (changing) {
            playerData.err("command.rtv.changing")
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
        val needed = requiredFor(players.count { it.uuid != uuid })
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

    private fun startTimeout(seconds: Int) {
        timeout?.cancel()
        timeout = Timer.schedule({
            if (votes.isNotEmpty()) {
                broadcast("command.rtv.timeout")
                clearVotes()
            }
        }, seconds.toFloat())
    }

    private fun clearVotes() {
        timeout?.cancel()
        timeout = null
        votes.clear()
    }

    private fun broadcast(key: String, vararg args: Any) {
        players.forEach { it.send(key, *args) }
    }
}
