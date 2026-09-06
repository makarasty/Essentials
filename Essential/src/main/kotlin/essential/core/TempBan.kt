package essential.core

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
        val expired = suspendTransaction {
            PlayerTable.select(PlayerTable.uuid)
                .where { PlayerTable.banExpireDate lessEq now }
                .map { it[PlayerTable.uuid] }
                .toList()
        } + orphaned().filterValues { it <= now }.keys

        for (uuid in expired) {
            clearBanExpire(uuid)
            Vars.netServer?.admins?.unbanPlayerID(uuid)
        }
    }

    suspend fun setBanExpire(uuid: String, expire: LocalDateTime) {
        val data = findPlayerData(uuid) ?: getPlayerData(uuid)
        if (data != null) {
            data.banExpireDate = expire
            data.update()
            return
        }
        pluginData.data.tempBans[uuid] = expire.toString()
        pluginData.update()
    }

    suspend fun clearBanExpire(uuid: String) {
        findPlayerData(uuid)?.banExpireDate = null
        suspendTransaction {
            PlayerTable.update({ PlayerTable.uuid eq uuid }) { it[banExpireDate] = null }
        }
        if (pluginData.data.tempBans.remove(uuid) != null) pluginData.update()
    }

    private fun orphaned(): Map<String, LocalDateTime> =
        pluginData.data.tempBans.mapNotNull { (uuid, date) ->
            runCatching { uuid to LocalDateTime.parse(date) }.getOrNull()
        }.toMap()
}
