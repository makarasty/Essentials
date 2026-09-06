package essential.core

import arc.util.Log
import arc.util.Timer
import essential.common.database.data.getPlayerData
import essential.common.database.data.update
import essential.common.database.table.PlayerTable
import essential.common.pluginData
import essential.common.systemTimezone
import essential.common.util.findPlayerData
import essential.core.Main.Companion.scope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import mindustry.Vars
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
object TempBan {
    private const val INTERVAL = 30f

    fun start() {
        Timer.schedule({ scope.launch { tick() } }, INTERVAL, INTERVAL)
    }

    suspend fun tick() {
        val now = Clock.System.now().toLocalDateTime(systemTimezone)
        val stored = runCatching {
            suspendTransaction {
                PlayerTable.select(PlayerTable.uuid)
                    .where { PlayerTable.banExpireDate lessEq now }
                    .map { it[PlayerTable.uuid] }
                    .toList()
            }
        }.onFailure { Log.err("Failed to read temp ban expiries from the database", it) }.getOrDefault(emptyList())
        val expired = (stored + orphaned().filterValues { it <= now }.keys).toSet()

        for (uuid in expired) {
            clearBanExpire(uuid)
            Vars.netServer?.admins?.unbanPlayerID(uuid)
        }
    }

    suspend fun setBanExpire(uuid: String, expire: LocalDateTime) {
        val stored = runCatching {
            val data = findPlayerData(uuid)?.takeIf { !it.temporary } ?: getPlayerData(uuid)
            if (data != null) {
                data.banExpireDate = expire
                data.update()
            }
            data != null
        }.onFailure { Log.err("Failed to store the temp ban expiry of $uuid in the database, keeping it in plugin data", it) }
            .getOrDefault(false)
        if (stored) return
        pluginData.data.tempBans[uuid] = expire.toString()
        pluginData.update()
    }

    suspend fun clearBanExpire(uuid: String) {
        findPlayerData(uuid)?.banExpireDate = null
        runCatching {
            suspendTransaction {
                PlayerTable.update({ PlayerTable.uuid eq uuid }) { it[banExpireDate] = null }
            }
        }.onFailure { Log.err("Failed to clear the temp ban expiry of $uuid in the database", it) }
        if (pluginData.data.tempBans.remove(uuid) != null) pluginData.update()
    }

    private fun orphaned(): Map<String, LocalDateTime> =
        pluginData.data.tempBans.mapNotNull { (uuid, date) ->
            runCatching { uuid to LocalDateTime.parse(date) }.getOrNull()
        }.toMap()
}
