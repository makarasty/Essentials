package essential.common.database.data

import essential.common.database.LEGACY_BASELINE_VERSION
import essential.common.database.data.plugin.WarpBlock
import essential.common.database.data.plugin.WarpCount
import essential.common.database.data.plugin.WarpTotal
import essential.common.database.data.plugin.WarpZone
import essential.common.database.table.PluginTable
import essential.common.database.data.update as updateRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import ksp.table.GenerateCode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

@GenerateCode
@Serializable
data class PluginData(
    val id: UInt,
    var databaseVersion: UByte,
    var hubMapName: String?,
    var data: DisplayData
) {
    /**
     * The row as this server last read or wrote it, with the serialised blob copied rather than
     * shared. [update] reads it to tell which collections of [data] this server itself changed.
     */
    @Transient
    var dbSnapshot: PluginData? = null

    /**
     * Six servers share this row, and each of them holds a copy taken at its own boot, so writing the
     * blob back whole erased everything the other five had written since. The stored row is re-read
     * inside the write transaction and merged element by element against the copy this server read.
     *
     * What is still lost: two servers changing the *same* element between one server's read and its
     * write - the same player's ban, the same warp - and, for map ratings, two servers rating the same
     * map, because those are merged per map name rather than per player. Only splitting this blob into
     * real tables removes that.
     */
    suspend fun update(): Boolean {
        val previous = dbSnapshot
        return try {
            suspendTransaction {
                val base = previous?.data
                if (base != null) {
                    // By id, not getPluginData()'s oldest row: when several servers have each inserted
                    // their own row - which createPluginData only narrows, never prevents - the oldest
                    // is not necessarily the one updateRow() is about to write.
                    // Qualified: inside a transaction lambda, a bare `id` is the transaction's own.
                    val rowId = this@PluginData.id
                    val stored = PluginTable.selectAll()
                        .mapToPluginDataList()
                        .firstOrNull { it.id == rowId }
                        ?.data
                    if (stored != null) {
                        this@PluginData.data = this@PluginData.data.mergedOnto(stored, base)
                    }
                }
                updateRow()
            }
        } catch (e: Throwable) {
            // updateRow() records the values it sent from inside this transaction, which has not
            // committed yet. If it never does, that record would claim a state the database never saw
            // and the next merge would treat this server's changes as already stored and drop them.
            dbSnapshot = previous
            throw e
        }
    }
}

@Serializable
data class DisplayData(
    val warpZone: ArrayList<WarpZone> = arrayListOf(),
    val warpCount: ArrayList<WarpCount> = arrayListOf(),
    val warpTotal: ArrayList<WarpTotal> = arrayListOf(),
    val warpBlock: ArrayList<WarpBlock> = arrayListOf(),
    val blacklistedNames: ArrayList<String> = arrayListOf(),
    val mapRatings: HashMap<String, HashMap<String, Boolean>> = hashMapOf(),
    val tempBans: HashMap<String, String> = hashMapOf()
)

/**
 * The stored collection, with what this server deleted taken out and what it added put back. Per
 * element rather than per collection, so two servers banning two different players do not erase each
 * other.
 */
private fun <T> List<T>.mergedOnto(base: List<T>, stored: List<T>): ArrayList<T> {
    val deleted = base.filterNot { it in this }
    val result = ArrayList(stored.filterNot { it in deleted })
    for (element in this) if (element !in result) result.add(element)
    return result
}

private fun <K, V> Map<K, V>.mergedOnto(base: Map<K, V>, stored: Map<K, V>): HashMap<K, V> {
    val result = HashMap(stored)
    for (key in base.keys) if (!containsKey(key)) result.remove(key)
    for ((key, value) in this) if (base[key] != value) result[key] = value
    return result
}

/**
 * A new [DisplayData] is returned rather than the collections being cleared and refilled in place:
 * Trigger's ping loop takes `val data = pluginData.data` and then iterates `data.warpBlock` with
 * `iterator.remove()` and indexes `data.warpCount`, on its own thread, and its exception handler exits
 * the process - emptying those lists underneath it would turn a saved warp edit into a server
 * shutdown. Every other reader dereferences `pluginData.data` afresh and so picks this up.
 */
private fun DisplayData.mergedOnto(stored: DisplayData, base: DisplayData) = DisplayData(
    warpZone = warpZone.mergedOnto(base.warpZone, stored.warpZone),
    warpCount = warpCount.mergedOnto(base.warpCount, stored.warpCount),
    warpTotal = warpTotal.mergedOnto(base.warpTotal, stored.warpTotal),
    warpBlock = warpBlock.mergedOnto(base.warpBlock, stored.warpBlock),
    blacklistedNames = blacklistedNames.mergedOnto(base.blacklistedNames, stored.blacklistedNames),
    mapRatings = mapRatings.mergedOnto(base.mapRatings, stored.mapRatings),
    tempBans = tempBans.mergedOnto(base.tempBans, stored.tempBans)
)

/** Read plugin data */
suspend fun getPluginData(): PluginData? {
    return suspendTransaction {
        // Several servers may each have inserted their own row; always read the oldest one so
        // every server converges on the same row instead of picking an arbitrary insert order.
        PluginTable.selectAll()
            .orderBy(PluginTable.id, SortOrder.ASC)
            .limit(1)
            .mapToPluginDataList()
            .firstOrNull()
    }
}

/** Create plugin data */
suspend fun createPluginData(): PluginData {
    val displayData = DisplayData()
    return suspendTransaction {
        // Re-check inside the transaction: another server may have inserted its row between the
        // caller's getPluginData() and this insert, so return that row instead of inserting a duplicate.
        // This only narrows the race, it doesn't close it - there's no unique constraint or lock backing it.
        getPluginData() ?: run {
            PluginTable.insert {
                // A row created here belongs to a schema SchemaUtils just built at the current shape,
                // so it starts at the baseline. Zero sent the next start into the legacy upgrade path,
                // whose scripts rename tables this database never had.
                it[PluginTable.databaseVersion] = LEGACY_BASELINE_VERSION
                it[PluginTable.hubMapName] = null
                it[PluginTable.data] = Json.encodeToString(displayData)
            }
            getPluginData() ?: throw IllegalStateException("PluginData not found after creation")
        }
    }
}
