package essential.common.database.data

import arc.util.Log
import essential.common.database.table.ServerRoutingTable
import essential.common.systemTimezone
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

data class ServerRoutingData(
    val id: UInt,
    val playerUuid: String,
    val hubServerName: String,
    val targetServerName: String,
    val targetPort: Int,
    val hubConnectionTime: LocalDateTime,
    val routingAllowedTime: LocalDateTime,
    val isUsed: Boolean,
    val usedTime: LocalDateTime?,
    val expiresAt: LocalDateTime
)

fun ResultRow.toServerRoutingData() = ServerRoutingData(
    id = this[ServerRoutingTable.id],
    playerUuid = this[ServerRoutingTable.playerUuid],
    hubServerName = this[ServerRoutingTable.hubServerName],
    targetServerName = this[ServerRoutingTable.targetServerName],
    targetPort = this[ServerRoutingTable.targetPort],
    hubConnectionTime = this[ServerRoutingTable.hubConnectionTime],
    routingAllowedTime = this[ServerRoutingTable.routingAllowedTime],
    isUsed = this[ServerRoutingTable.isUsed],
    usedTime = this[ServerRoutingTable.usedTime],
    expiresAt = this[ServerRoutingTable.expiresAt]
)

/**
 * Grants routing permission via the hub server.
 */
@OptIn(ExperimentalTime::class)
suspend fun grantRoutingPermission(
    playerUuid: String,
    hubServerName: String,
    targetServerName: String,
    targetPort: Int,
    hubConnectionTime: LocalDateTime,
    validSeconds: Int = 60
): ServerRoutingData? {
    val routingAllowedTime = Clock.System.now().toLocalDateTime(systemTimezone)
    val expiresAt = (Clock.System.now() + validSeconds.seconds).toLocalDateTime(systemTimezone)

    pruneExpiredRoutingPermissions()

    return suspendTransaction {
        ServerRoutingTable.insert {
            it[ServerRoutingTable.playerUuid] = playerUuid
            it[ServerRoutingTable.hubServerName] = hubServerName
            it[ServerRoutingTable.targetServerName] = targetServerName
            it[ServerRoutingTable.targetPort] = targetPort
            it[ServerRoutingTable.hubConnectionTime] = hubConnectionTime
            it[ServerRoutingTable.routingAllowedTime] = routingAllowedTime
            it[ServerRoutingTable.isUsed] = false
            it[ServerRoutingTable.usedTime] = null
            it[ServerRoutingTable.expiresAt] = expiresAt
        }
        ServerRoutingTable.selectAll()
            .where { ServerRoutingTable.playerUuid eq playerUuid }
            .orderBy(ServerRoutingTable.id, SortOrder.DESC)
            .limit(1)
            .map { row -> row.toServerRoutingData() }
            .singleOrNull()
    }
}

/**
 * How many expired rows one grant clears.
 *
 * The table has been growing since the feature landed, so the first grant after this ships meets the
 * whole accumulated backlog. Bounded, that is a short transaction repeated over the next few hundred
 * grants; unbounded, it is one long lock hold on a table five other servers are reading.
 */
private const val PRUNE_LIMIT = 200

/**
 * Deletes expired routing permissions, a bounded slice at a time.
 *
 * This exists because [cleanupExpiredRoutingPermissions] never runs. Its only caller sits after the
 * ping thread's loop, on a path `shutdownNow`'s interrupt jumps straight past, so rows were only ever
 * inserted. Every warp move and AFK transfer comes through a grant, so clearing here keeps the table
 * to roughly the grants inside one validity window - for as long as grants keep arriving. A server
 * that stops granting keeps whatever it had.
 *
 * In its own transaction and its own `runCatching`, deliberately: a grant is a player waiting to be
 * moved, and housekeeping must not be able to cost them that. Sharing the grant's transaction would
 * also have put a scan of this table inside the hot path of every warp, against a `consumeRouting`
 * `UPDATE` reaching the same rows by a different index.
 *
 * The cutoff is the database's own clock, not this server's. `expires_at` is a naive local time with no
 * offset, and six machines writing to one table do not agree on what "now" is; a server running a minute
 * fast would delete grants that are still valid on the server that issued them, and the symptom would be
 * a warp refused for no visible reason on one machine and working on the other five. `CURRENT_TIMESTAMP`
 * is evaluated where the rows live, which is the one clock all six share.
 *
 * The predicate is the expiry and nothing else - no player, no server. A clause narrower than this would
 * delete a live permission somebody is about to use; a clause wider would not be an expiry at all.
 */
private suspend fun pruneExpiredRoutingPermissions() {
    runCatching {
        suspendTransaction {
            ServerRoutingTable.deleteWhere(limit = PRUNE_LIMIT) { expiresAt less CurrentDateTime }
        }
    }.onFailure {
        Log.warn("[ServerRouting] could not clear expired routing permissions: ${it.message}")
    }
}

/**
 * Checks whether the player has permission to connect to a specific server.
 */
@OptIn(ExperimentalTime::class)
suspend fun checkRoutingPermission(playerUuid: String, targetServerName: String, targetPort: Int): Boolean {
    val now = Clock.System.now().toLocalDateTime(systemTimezone)
    
    return suspendTransaction {
        val sort = ServerRoutingTable.selectAll().where {
            (ServerRoutingTable.playerUuid eq playerUuid) and
            (ServerRoutingTable.targetServerName eq targetServerName) and
            (ServerRoutingTable.targetPort eq targetPort) and
            (ServerRoutingTable.isUsed eq false) and
            (ServerRoutingTable.expiresAt greater now)
        }.count()

        sort > 0
    }
}

/**
 * Atomically consumes one permission for the intended destination server.
 */
@OptIn(ExperimentalTime::class)
suspend fun consumeRoutingPermission(playerUuid: String, targetServerName: String, targetPort: Int): Boolean {
    val now = Clock.System.now().toLocalDateTime(systemTimezone)
    
    return suspendTransaction {
        val permissionId = ServerRoutingTable.select(ServerRoutingTable.id).where {
            (ServerRoutingTable.playerUuid eq playerUuid) and
            (ServerRoutingTable.targetServerName eq targetServerName) and
            (ServerRoutingTable.targetPort eq targetPort) and
            (ServerRoutingTable.isUsed eq false) and
            (ServerRoutingTable.expiresAt greater now)
        }.limit(1).singleOrNull()?.get(ServerRoutingTable.id) ?: return@suspendTransaction false

        val updatedCount = ServerRoutingTable.update({
            (ServerRoutingTable.id eq permissionId) and
            (ServerRoutingTable.isUsed eq false) and
            (ServerRoutingTable.expiresAt greater now)
        }) {
            it[isUsed] = true
            it[usedTime] = now
        }
        updatedCount > 0
    }
}

/**
 * Clean up expired routing permissions.
 *
 * Its only caller, `Trigger.kt`'s ping thread, sits after that thread's loop and is jumped over by the
 * `InterruptedException` that `shutdownNow` raises, so in practice this does not run. Expired rows are
 * cleared by [pruneExpiredRoutingPermissions] on every grant instead. Left in place because moving
 * that call is a change to a file this cluster does not own.
 */
@OptIn(ExperimentalTime::class)
suspend fun cleanupExpiredRoutingPermissions() {
    val now = Clock.System.now().toLocalDateTime(systemTimezone)
    
    suspendTransaction {
        ServerRoutingTable.deleteWhere {
            expiresAt less now
        }
    }
}
