package essential.common.util

import essential.common.database.data.PlayerData
import essential.common.players
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Get current time. The Format is "yyyy-MM-dd HH:mm:ss.SSS" */
@OptIn(ExperimentalTime::class)
fun currentTime(): String {
    return Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()).toHString()
}


/** Get player information by UUID from the plugin */
fun findPlayerData(uuid: String): PlayerData? {
    return players.find { data -> data.uuid == uuid }
}
