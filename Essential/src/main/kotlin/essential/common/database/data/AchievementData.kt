package essential.common.database.data

import arc.util.Log
import kotlinx.coroutines.CancellationException
import essential.common.database.table.AchievementTable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Data class for player achievements
 */
data class AchievementData(
    val id: UInt,
    val playerId: UInt,
    val achievementName: String,
    val completedAt: LocalDateTime
)

/**
 * Check if a player has completed an achievement
 */
suspend fun hasAchievement(playerData: PlayerData, achievementName: String): Boolean {
    return suspendTransaction {
        val query = AchievementTable.select(AchievementTable.id)
            .where { 
                (AchievementTable.playerId eq playerData.id) and
                (AchievementTable.achievementName eq achievementName)
            }

        query.firstOrNull() != null
    }
}

/**
 * Set an achievement as completed for a player
 */
suspend fun setAchievement(playerData: PlayerData, achievementName: String) {
    // Reading an absent row takes no lock, so the player earning this on two of the six servers at
    // once passes the check twice and the unique (player_id, achievement_name) index refuses the
    // second insert. That refusal means the row is there, which is all this function wanted.
    val refused = runCatching {
        suspendTransaction {
            // Check if the achievement is already completed
            val query = AchievementTable.select(AchievementTable.id)
                .where {
                    (AchievementTable.playerId eq playerData.id) and
                    (AchievementTable.achievementName eq achievementName)
                }

            val existing = query.firstOrNull()

            // If not, create a new record
            if (existing == null) {
                AchievementTable.insert {
                    it[AchievementTable.playerId] = playerData.id
                    it[AchievementTable.achievementName] = achievementName
                    // completedAt will be set automatically by the default value
                }
            }
        }
    }.exceptionOrNull()

    if (refused is CancellationException) throw refused

    // Told apart by re-reading the table rather than by the exception's text, because the code that
    // says "duplicate key" differs per engine and a refusal for any other reason has to stay an error.
    //
    // The report is on the far side of that read, not before it. `refused` is any exception this
    // transaction threw - a dropped connection, a lock wait, a deadlock - so a line printed ahead of
    // the read would print on a genuine failure too, one info ahead of the err, on exactly the incident
    // an operator is chasing. Past the read the two are already separated: the row is there, so
    // something else put it there, which is the concurrent award this function exists to tolerate.
    if (refused != null) {
        if (!hasAchievement(playerData, achievementName)) {
            Log.err("Could not record achievement $achievementName for ${playerData.uuid}", refused)
            return
        }
        Log.info("Achievement insert refused for ${playerData.uuid}/$achievementName, row is already there: ${refused.message}")
    }

    // Add to player's achievement status list
    if (!playerData.achievementStatus.contains(achievementName)) {
        playerData.achievementStatus.add(achievementName)
    }
}

/**
 * Get all completed achievements for a player
 */
suspend fun getPlayerAchievements(playerData: PlayerData): List<AchievementData> {
    return suspendTransaction {
        AchievementTable.select(AchievementTable.columns)
            .where { AchievementTable.playerId eq playerData.id }
            .map { row ->
                AchievementData(
                    id = row[AchievementTable.id],
                    playerId = row[AchievementTable.playerId],
                    achievementName = row[AchievementTable.achievementName],
                    completedAt = row[AchievementTable.completedAt]
                )
            }.toList()
    }
}
