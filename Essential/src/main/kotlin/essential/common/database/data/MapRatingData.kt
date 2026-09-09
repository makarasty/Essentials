package essential.common.database.data

import arc.util.Log
import kotlinx.coroutines.CancellationException
import essential.common.database.table.MapRatingTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import ksp.table.GenerateCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

@GenerateCode
data class MapRatingData(
    val id: UInt,
    val mapName: String,
    val mapHash: String,
    val playerUuid: String,
    val difficulty: Int,
    val rating: Int,
)

/**
 * Create a new map rating
 */
suspend fun createMapRating(
    mapName: String,
    mapHash: String,
    playerUuid: String,
    difficulty: Int,
    rating: Int
): MapRatingData {
    return suspendTransaction {
        MapRatingTable.insert {
            it[MapRatingTable.mapName] = mapName
            it[MapRatingTable.mapHash] = mapHash
            it[MapRatingTable.playerUuid] = playerUuid
            it[MapRatingTable.difficulty] = difficulty
            it[MapRatingTable.rating] = rating
        }
        MapRatingTable.selectAll()
            .where { (MapRatingTable.playerUuid eq playerUuid) and (MapRatingTable.mapName eq mapName) }
            .mapToMapRatingDataList()
            .single()
    }
}

/**
 * Get a map rating by player UUID and map name
 */
suspend fun getMapRating(playerUuid: String, mapName: String): MapRatingData? {
    return suspendTransaction {
        MapRatingTable.selectAll()
            .where { (MapRatingTable.playerUuid eq playerUuid) and (MapRatingTable.mapName eq mapName) }
            .mapToMapRatingDataList()
            .firstOrNull()
    }
}

/**
 * Get the UUIDs of the given players that already rated the map, in a single query
 */
suspend fun getRatedPlayerUuids(mapName: String, playerUuids: List<String>): Set<String> {
    if (playerUuids.isEmpty()) return emptySet()
    return suspendTransaction {
        MapRatingTable.select(MapRatingTable.playerUuid)
            .where { (MapRatingTable.mapName eq mapName) and (MapRatingTable.playerUuid inList playerUuids) }
            .map { it[MapRatingTable.playerUuid] }
            .toSet()
    }
}

/**
 * Get all map ratings for a specific map
 */
suspend fun getMapRatings(mapName: String): List<MapRatingData> {
    return suspendTransaction {
        MapRatingTable.selectAll()
            .where { MapRatingTable.mapName eq mapName }
            .mapToMapRatingDataList()
    }
}

/**
 * Get all map ratings by a specific player
 */
suspend fun getPlayerMapRatings(playerUuid: String): List<MapRatingData> {
    return suspendTransaction {
        MapRatingTable.selectAll()
            .where { MapRatingTable.playerUuid eq playerUuid }
            .mapToMapRatingDataList()
    }
}

/**
 * Update an existing map rating or create a new one if it doesn't exist
 */
suspend fun updateOrCreateMapRating(
    mapName: String,
    mapHash: String,
    playerUuid: String,
    difficulty: Int,
    rating: Int
): MapRatingData {
    val existing = getMapRating(playerUuid, mapName)
    return if (existing != null) {
        if (existing.difficulty != difficulty || existing.rating != rating) {
            suspendTransaction {
                MapRatingTable.update({ (MapRatingTable.playerUuid eq playerUuid) and (MapRatingTable.mapName eq mapName) }) {
                    it[MapRatingTable.difficulty] = difficulty
                    it[MapRatingTable.rating] = rating
                }
            }
            getMapRating(playerUuid, mapName)!!
        } else {
            existing
        }
    } else {
        // The read above and the insert below are separate transactions, so two of the six servers
        // rating the same map for the same player both find nothing and the unique
        // (player_uuid, map_name) index refuses the loser. The loser's rating is the newer one, so it
        // is applied to the row that won rather than dropped on the floor.
        //
        // On a schema the legacy scripts built there is no such index - v5.sql drops the one
        // map_ratings had and adds none back - so what fails there is createMapRating's own single(),
        // which now sees two rows. The same fallback covers it: the update writes both duplicates and
        // the caller gets a row back rather than an exception.
        runCatching { createMapRating(mapName, mapHash, playerUuid, difficulty, rating) }.getOrElse { refused ->
            if (refused is CancellationException) throw refused
            Log.info("Map rating for $playerUuid on $mapName could not be created, updating instead: ${refused.message}")
            suspendTransaction {
                MapRatingTable.update({ (MapRatingTable.playerUuid eq playerUuid) and (MapRatingTable.mapName eq mapName) }) {
                    it[MapRatingTable.difficulty] = difficulty
                    it[MapRatingTable.rating] = rating
                }
            }
            getMapRating(playerUuid, mapName)
                ?: throw IllegalStateException("Map rating for $playerUuid on $mapName is missing after creation", refused)
        }
    }
}

/**
 * Migrate map ratings from PluginData to the new MapRating table
 */
suspend fun migrateMapRatingsFromPluginData(pluginData: PluginData) {
    suspendTransaction {
        for ((mapName, ratings) in pluginData.data.mapRatings) {
            for ((playerUuid, isUpvote) in ratings) {
                try {
                    MapRatingTable.insert {
                        it[MapRatingTable.mapName] = mapName
                        it[MapRatingTable.mapHash] = ""
                        it[MapRatingTable.playerUuid] = playerUuid
                        it[MapRatingTable.difficulty] = 3
                        it[MapRatingTable.rating] = if (isUpvote) 5 else 1
                    }
                } catch (e: Exception) {
                    println("Error migrating map rating for map $mapName and player $playerUuid: ${e.message}")
                }
            }
        }
    }
}
