package essential.common.database.table

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.json

object PlayerBannedTable : Table("player_banned") {
    val id = uinteger("id").autoIncrement().uniqueIndex()
    val names = json<List<String>>("names", Json)
    val ips = json<List<String>>("ips", Json)
    val uuid = varchar("uuid", 25)
    val reason = varchar("reason", 256)
    val date = long("date")

    /**
     * Which server issued this ban. Six servers share the table and each writes its own row, so an
     * unban has to be able to lift one of them without lifting the other five. Nullable because a row
     * written before this column existed says nothing about who owns it.
     */
    val serverId = varchar("server_id", 100).nullable().default(null)

    override val primaryKey = PrimaryKey(id)
}