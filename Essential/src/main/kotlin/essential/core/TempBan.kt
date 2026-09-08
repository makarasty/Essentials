package essential.core

import arc.Core
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
object TempBan {
    private const val INTERVAL = 30f

    /** Bans the scheduler is lifting right now; their unban event must not wipe the expiry. */
    private val lifting = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        // The ban list is game state, so it is read on the main thread and handed to the sweep.
        Timer.schedule({ Core.app.post { scope.launch { tick(localBans()) } } }, INTERVAL, INTERVAL)
    }

    private fun localBans(): Set<String> =
        Vars.netServer?.admins?.banned?.toList()?.map { it.id }?.toSet() ?: emptySet()

    /** For tests, which already run on the application thread. */
    suspend fun tick() = tick(localBans())

    suspend fun tick(banned: Set<String>) {
        if (banned.isEmpty()) return
        val now = Clock.System.now().toLocalDateTime(systemTimezone)

        // Only the server that issued a temp ban holds it in Mindustry's own list, so the sweep
        // lifts what is expired in the database AND banned here. The expiry column is left
        // alone: a timestamp in the past already says "over", and clearing it would hide the
        // row from a sibling server that holds its own ban on the same player.
        val stored = runCatching {
            suspendTransaction {
                PlayerTable.select(PlayerTable.uuid)
                    .where { PlayerTable.banExpireDate lessEq now }
                    .map { it[PlayerTable.uuid] }
                    .toList()
            }
        }.onFailure { Log.err("Failed to read temp ban expiries from the database", it) }.getOrDefault(emptyList())
        val orphanedExpired = orphaned().filterValues { it <= now }.keys

        val expired = (stored + orphanedExpired).filter { it in banned }.toSet()
        if (expired.isEmpty()) return
        lifting += expired
        // Lifting a ban changes game state and fires PlayerUnbanEvent; that belongs on the main thread.
        Core.app.post { expired.forEach { Vars.netServer.admins.unbanPlayerID(it) } }
        if (orphanedExpired.any { it in expired }) {
            expired.forEach { pluginData.data.tempBans.remove(it) }
            pluginData.update()
        }
    }

    /** Called from the PlayerUnbanEvent handler: a human unban forgets the expiry, the scheduler's does not. */
    suspend fun onUnban(uuid: String) {
        if (lifting.remove(uuid)) return
        clearBanExpire(uuid)
    }

    suspend fun setBanExpire(uuid: String, expire: LocalDateTime) {
        val stored = runCatching {
            val data = findPlayerData(uuid)?.takeIf { !it.temporary } ?: getPlayerData(uuid)
            if (data != null) {
                data.banExpireDate = expire
                data.update()
            } else {
                false
            }
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
