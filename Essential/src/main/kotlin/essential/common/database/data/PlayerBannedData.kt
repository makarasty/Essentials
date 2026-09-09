package essential.common.database.data

import arc.util.Log
import essential.common.database.table.PlayerBannedTable
import essential.common.database.thisServerId
import essential.common.util.escapeLike
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mindustry.gen.Playerc
import mindustry.net.Administration
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

data class PlayerBannedData(
    val id: UInt,
    var names: List<String>,
    var ips: List<String>,
    var uuid: String,
    var reason: String,
    var date: Long
)

/** Add banned player information to the database. */
suspend fun createBanInfo(data: Administration.PlayerInfo, reason: String?) {
    return suspendTransaction {
        PlayerBannedTable.insert {
            it[names] = data.names.toList()
            it[ips] = data.ips.toList()
            it[uuid] = data.id
            it[PlayerBannedTable.reason] = reason ?: "Banned by an administrator."
            it[date] = System.currentTimeMillis()
            it[serverId] = thisServerId
        }
    }
}

/**
 * Lifts one ban row for this uuid rather than every row for it.
 *
 * Six servers share this table and each of them writes its own row, so deleting by uuid alone let one
 * server's expiring temp ban erase the permanent ban another server had issued - and since the banning
 * server goes on refusing the player from its own local list, nobody there would notice. A ban this
 * server issued goes first, then the oldest row nobody owns - one written before
 * [PlayerBannedTable.serverId] existed - and then the oldest row of all. An unban that matched nothing
 * would be worse than lifting the wrong server's ban: the engine fires PlayerUnbanEvent only while
 * the local list still holds the player, so a second attempt reaches nothing and the ban is permanent.
 * On an upgraded database, where every row is unowned, which of six bans is lifted is arbitrary; only
 * bans written after this column existed can be told apart.
 */
suspend fun removeBanInfoByUUID(uuid: String) {
    return suspendTransaction {
        val mine = thisServerId
        val rows = PlayerBannedTable
            .select(PlayerBannedTable.id, PlayerBannedTable.serverId)
            .where { PlayerBannedTable.uuid eq uuid }
            .orderBy(PlayerBannedTable.id)
            .map { it[PlayerBannedTable.id] to it[PlayerBannedTable.serverId] }
            .toList()

        val target = rows.firstOrNull { it.second == mine }
            ?: rows.firstOrNull { it.second == null }
            ?: rows.firstOrNull()
            ?: return@suspendTransaction
        PlayerBannedTable.deleteWhere { PlayerBannedTable.id eq target.first }
    }
}

/** Unban a player by IP. */
suspend fun removeBanInfoByIP(ip: String) {
    return suspendTransaction {
        PlayerBannedTable.deleteWhere {
            PlayerBannedTable.ips
                .castTo(TextColumnType())
                .like(LikePattern("%\"${ip.escapeLike()}\"%", '\\'))
        }
    }
}

/** Check whether the player is banned. */
suspend fun checkPlayerBanned(player: Playerc): Boolean {
    return checkPlayerBanned(player.uuid(), player.ip(), player.name())
}

/** Check whether the player is banned. */
suspend fun checkPlayerBanned(uuid: String, ip: String, name: String): Boolean {
    try {
        return suspendTransaction {
            val nameCond =
                PlayerBannedTable.names
                    .castTo(TextColumnType())
                    .like(LikePattern("%\"${name.escapeLike()}\"%", '\\'))

            val ipCond =
                PlayerBannedTable.ips
                    .castTo(TextColumnType())
                    .like(LikePattern("%\"${ip.escapeLike()}\"%", '\\'))

            // One column rather than selectAll(): the connect check has to keep working on a database
            // whose ALTER for server_id was refused, and a select naming that column would fail there -
            // into the catch below, which returns false and lets every banned player in.
            PlayerBannedTable
                .select(PlayerBannedTable.id)
                .where { (PlayerBannedTable.uuid eq uuid) or nameCond or ipCond }
                .limit(1)
                .count() > 0
        }
    } catch (e: Exception) {
        Log.err("Failed to check if player is banned", e)
        return false
    }
}

/** Check whether the player is banned by IP or UUID. */
suspend fun checkPlayerBannedByIpOrUuid(uuid: String, ip: String): Boolean {
    try {
        return suspendTransaction {
            val ipCond =
                PlayerBannedTable.ips
                    .castTo(TextColumnType())
                    .like(LikePattern("%\"${ip.escapeLike()}\"%", '\\'))

            PlayerBannedTable
                .select(PlayerBannedTable.id)
                .where { (PlayerBannedTable.uuid eq uuid) or ipCond }
                .limit(1)
                .count() > 0
        }
    } catch (e: Exception) {
        Log.err("Failed to check if player is banned by IP or UUID", e)
        return false
    }
}
