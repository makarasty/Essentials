package essential.common.util

import essential.common.CommandCooldownTuning
import essential.common.database.data.PlayerData
import essential.common.players
import essential.common.timeSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark

/** Get current time. The Format is "yyyy-MM-dd HH:mm:ss.SSS" */
@OptIn(ExperimentalTime::class)
fun currentTime(): String {
    return Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()).toHString()
}


/** Get player information by UUID from the plugin */
fun findPlayerData(uuid: String): PlayerData? = players.byUuid(uuid)

// shortcut: one entry per command and uuid, never pruned; a few bytes each, prune if it ever shows up in a heap dump.
private val commandCooldowns = ConcurrentHashMap<String, TimeMark>()

/**
 * False, and the player is told so, while [command] is cooling down for them; otherwise starts the
 * cooldown and answers true. For commands that query the shared database, so one player cannot keep
 * nine servers' pool busy. Admins are exempt.
 */
fun PlayerData.passesCooldown(command: String): Boolean {
    if (player.admin()) return true
    val key = "$command:$uuid"
    val until = commandCooldowns[key]
    if (until != null && !until.hasPassedNow()) {
        err("command.cooldown")
        return false
    }
    commandCooldowns[key] = timeSource.markNow() + CommandCooldownTuning.DATABASE_COMMAND_SECONDS.seconds
    return true
}
