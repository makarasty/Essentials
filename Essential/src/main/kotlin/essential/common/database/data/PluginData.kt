package essential.common.database.data

import essential.common.database.data.plugin.WarpBlock
import essential.common.database.data.plugin.WarpCount
import essential.common.database.data.plugin.WarpTotal
import essential.common.database.data.plugin.WarpZone
import essential.common.database.table.PluginTable
import kotlinx.serialization.Serializable
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
)

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
                it[PluginTable.databaseVersion] = 0u
                it[PluginTable.hubMapName] = null
                it[PluginTable.data] = Json.encodeToString(displayData)
            }
            getPluginData() ?: throw IllegalStateException("PluginData not found after creation")
        }
    }
}
