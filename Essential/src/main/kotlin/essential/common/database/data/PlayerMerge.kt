package essential.common.database.data

import essential.common.database.table.AchievementTable
import essential.common.database.table.ContributionTable
import essential.common.database.table.PlayerTable
import essential.common.systemTimezone
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Merge player data from a source UUID into a target UUID according to the defined rules.
 * - Non-merged fields (kept from target): id, name, uuid, level, permission, account_id, account_pw, discord_id
 * - Summed: block_place_count, block_break_count, exp, total_played, attack_clear, wave_clear,
 *           pvp_win_count, pvp_lose_count, pvp_eliminated_count, pvp_mvp_count, attendance_days
 * - first_played: oldest of the two
 * - last_played: newest of the two
 * - last_login_date, last_logout_date: newest of the two (null-safe for logout)
 * - last_played_world_name/mode: use source (new) values
 * - ban_expire_date: latest of the two; isBanned adjusted to whether ban not expired
 * - status_data: achievement counters summed, see [mergeRecordCounters]
 * - player_achievements: the source's rows move to the target, earliest completed_at wins. Run this
 *   with both accounts offline: an award landing on the target between the read and the move
 *   collides with it.
 */
/** The target's achievement progress with the source's running totals added to it. */
internal fun mergeRecordCounters(
    to: Map<String, String>,
    from: Map<String, String>
): Map<String, String> {
    val merged = to.filterKeys { it.startsWith(RECORD_PREFIX) }.toMutableMap()

    for ((key, sourceValue) in from) {
        if (!key.startsWith(RECORD_PREFIX)) continue
        if (!isRunningTotalRecordKey(key)) continue

        val carried = sourceValue.toLongOrNull() ?: continue
        merged[key] = ((merged[key]?.toLongOrNull() ?: 0L) + carried).toString()
    }

    return merged
}

@OptIn(ExperimentalTime::class)
suspend fun mergePlayerAccounts(fromUuid: String, toUuid: String): String = suspendTransaction {
    if (fromUuid.equals(toUuid, ignoreCase = true)) {
        return@suspendTransaction "Source and target UUID must be different."
    }

    // Two reads: an ordinary one to find the ids, then one locking read by primary key.
    //
    // Every value written below is derived in Kotlin from what these two rows held when they were read,
    // and the write is an absolute value rather than a delta, so an exp gain another of the six servers
    // committed in between used to be overwritten by a sum computed before it existed. Summed columns
    // could have been written as SQL expressions instead, but the merged status_data is a JSON blob
    // built in Kotlin - the achievement counters this function exists to carry - and no column
    // expression can rebuild that. A locking read closes the window for every column at once.
    //
    // It has to lock by id rather than by uuid. players.id is the primary key on both the schema
    // SchemaUtils builds and the one v4.sql leaves behind, so this is an index lookup everywhere;
    // uuid's unique index exists only on the former, and a locking read that cannot use an index takes
    // an exclusive lock on every row it scans - one console mergeplayer would then block every join and
    // every stat write on all six servers for the length of the merge.
    //
    // Locking both ids in one statement means two merges running at once cannot take the same pair of
    // rows in opposite orders; the engine picks the order within the statement. That is a statement
    // property and not a claim about this function, which goes on to touch player_achievements and
    // player_contributions afterwards and can still deadlock against a concurrent setAchievement. The
    // header already says to run this with both accounts offline; that is why.
    val found = PlayerTable.select(PlayerTable.id, PlayerTable.uuid)
        .where { PlayerTable.uuid inList listOf(fromUuid, toUuid) }
        .map { it[PlayerTable.id] to it[PlayerTable.uuid] }
        .toList()

    val fromId = found.firstOrNull { it.second.equals(fromUuid, ignoreCase = true) }?.first
        ?: return@suspendTransaction "Source player not found: $fromUuid"
    val toId = found.firstOrNull { it.second.equals(toUuid, ignoreCase = true) }?.first
        ?: return@suspendTransaction "Target player not found: $toUuid"

    val locked = PlayerTable.selectAll()
        .where { PlayerTable.id inList listOf(fromId, toId) }
        .forUpdate(ForUpdateOption.ForUpdate)
        .mapToPlayerDataList()

    val from = locked.firstOrNull { it.id == fromId }
        ?: return@suspendTransaction "Source player not found: $fromUuid"
    val to = locked.firstOrNull { it.id == toId }
        ?: return@suspendTransaction "Target player not found: $toUuid"

    // Calculate merged values
    fun sumShort(a: Short, b: Short): Short = (a.toInt() + b.toInt()).coerceIn(0, Short.MAX_VALUE.toInt()).toShort()

    val mergedFirstPlayed: LocalDateTime = if (from.firstPlayed < to.firstPlayed) from.firstPlayed else to.firstPlayed
    val mergedLastPlayed: LocalDateTime = if (from.lastPlayed > to.lastPlayed) from.lastPlayed else to.lastPlayed

    val mergedLastLogin: LocalDateTime = if (from.lastLoginDate > to.lastLoginDate) from.lastLoginDate else to.lastLoginDate

    val mergedLastLogout: LocalDateTime? = when {
        from.lastLogoutDate == null && to.lastLogoutDate == null -> null
        from.lastLogoutDate == null -> to.lastLogoutDate
        to.lastLogoutDate == null -> from.lastLogoutDate
        else -> if (from.lastLogoutDate!! > to.lastLogoutDate!!) from.lastLogoutDate else to.lastLogoutDate
    }

    val mergedBanExpire: LocalDateTime? = when {
        from.banExpireDate == null && to.banExpireDate == null -> null
        from.banExpireDate == null -> to.banExpireDate
        to.banExpireDate == null -> from.banExpireDate
        else -> if (from.banExpireDate!! > to.banExpireDate!!) from.banExpireDate else to.banExpireDate
    }

    val nowLocal = Clock.System.now().toLocalDateTime(systemTimezone)
    // isBanned flag aligned to merged banExpireDate; if null -> false
    val mergedIsBanned: Boolean = mergedBanExpire?.let { it > nowLocal } ?: false

    // Apply update to target (keep non-merged fields from target as-is)
    PlayerTable.update({ PlayerTable.id eq to.id }) {
        it[blockPlaceCount] = to.blockPlaceCount + from.blockPlaceCount
        it[blockBreakCount] = to.blockBreakCount + from.blockBreakCount
        it[level] = to.level // unchanged per rule
        it[exp] = to.exp + from.exp
        it[firstPlayed] = mergedFirstPlayed
        it[lastPlayed] = mergedLastPlayed
        it[totalPlayed] = to.totalPlayed + from.totalPlayed
        it[attackClear] = to.attackClear + from.attackClear
        it[waveClear] = to.waveClear + from.waveClear
        it[pvpWinCount] = sumShort(to.pvpWinCount, from.pvpWinCount)
        it[pvpLoseCount] = sumShort(to.pvpLoseCount, from.pvpLoseCount)
        it[pvpEliminatedCount] = sumShort(to.pvpEliminatedCount, from.pvpEliminatedCount)
        it[pvpMvpCount] = sumShort(to.pvpMvpCount, from.pvpMvpCount)
        it[permission] = to.permission // unchanged per rule
        it[accountID] = to.accountID // unchanged per rule
        it[accountPW] = to.accountPW // unchanged per rule
        it[discordID] = to.discordID // unchanged per rule
        it[lastLoginDate] = mergedLastLogin
        it[lastLogoutDate] = mergedLastLogout
        it[lastPlayedWorldName] = from.lastPlayedWorldName // from source
        it[lastPlayedWorldMode] = from.lastPlayedWorldMode // from source
        it[isBanned] = mergedIsBanned
        it[banExpireDate] = mergedBanExpire
        it[attendanceDays] = to.attendanceDays + from.attendanceDays
        it[statusData] = statusJson.encodeToString(mergeRecordCounters(to.status, from.status))
    }

    // The source's achievement rows move to the target rather than being deleted. Only the
    // achievements whose criterion is a summed counter can be re-derived on the target's next join:
    // the reload skips hidden ones, the ones resting on a key that must not be summed have nothing
    // left to re-derive from, and the APM ones read a field that is never persisted at all - so a
    // delete here loses an arbitrary subset of them for good.
    val earnedOnTarget = AchievementTable
        .select(AchievementTable.achievementName, AchievementTable.completedAt)
        .where { AchievementTable.playerId eq to.id }
        .map { it[AchievementTable.achievementName] to it[AchievementTable.completedAt] }
        .toList()
        .toMap()

    val earnedOnSource = AchievementTable
        .select(AchievementTable.achievementName, AchievementTable.completedAt)
        .where { AchievementTable.playerId eq from.id }
        .map { it[AchievementTable.achievementName] to it[AchievementTable.completedAt] }
        .toList()

    // Earliest wins on an achievement both hold, so three accounts merged in any order settle on the
    // same date.
    for ((name, earnedAt) in earnedOnSource) {
        if (earnedAt >= (earnedOnTarget[name] ?: continue)) continue

        AchievementTable.update({
            (AchievementTable.playerId eq to.id) and (AchievementTable.achievementName eq name)
        }) {
            it[completedAt] = earnedAt
        }
    }

    // Only a name the target does not already hold can move; the target's own row is the surviving
    // copy of the rest, and the source's duplicates go with its player row. The split is done here
    // rather than left to the unique index because the legacy MySQL schema declares only the foreign
    // key - `SchemaUtils` adds no index to a table it did not create.
    AchievementTable.update({
        (AchievementTable.playerId eq from.id) and
                (AchievementTable.achievementName notInList earnedOnTarget.keys)
    }) {
        it[playerId] = to.id
        // Not redundant. MySQL gives the first TIMESTAMP column in a table an implicit
        // ON UPDATE CURRENT_TIMESTAMP unless it was declared with a DEFAULT, and the hosts running this
        // predate the shipped DDL - one of them declaring it bare would have this statement rewrite every
        // carried date to now, silently, on the rows the carry exists to preserve. Assigning the column
        // its own value is what MySQL documents as the way to opt out.
        it[completedAt] = AchievementTable.completedAt
    }
    AchievementTable.deleteWhere { AchievementTable.playerId eq from.id }

    // Reassign the source player's contribution records to the target before deletion.
    ContributionTable.update({ ContributionTable.playerId eq from.id }) {
        it[ContributionTable.playerId] = to.id
    }
    PlayerTable.deleteWhere { PlayerTable.id eq from.id }

    return@suspendTransaction "Merged ${from.name} ($fromUuid) into ${to.name} ($toUuid)."
}
