package essential.common.database.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.ExperimentalTime

/**
 * Rollback history for the world currently loaded, in a local per-server H2 file.
 *
 * **Every column added here after the first release must be nullable with no default.** This file is not
 * the shared database: it is one `worldHistory.mv.db` per server, migrated only by the column repair in
 * `databaseInit`, and an operator's first move when a jar misbehaves is to put the previous one back.
 * Nullable-with-no-default is what makes that safe in both directions - Exposed projects and inserts an
 * explicit column list, so an older jar neither selects nor writes a column it does not know about, and
 * the rows it writes leave the new columns null, which is the same case as every row written before the
 * migration. A `NOT NULL` column, or one with a default an older jar cannot supply, breaks that.
 */
object WorldHistoryTable : Table("world_history") {
    val id = uinteger("id").autoIncrement()
    val time = long("time")
    val player = varchar("player", 100)
    val action = varchar("action", 50)
    val x = short("x")
    val y = short("y")
    val tile = varchar("tile", 100)
    val rotate = integer("rotate")
    val team = varchar("team", 50)
    val value = text("value").nullable()

    /**
     * Runtime class name of the config value [value] was flattened from, or null.
     *
     * The type is thrown away by `CoreEvent.addLog`'s `log.value?.toString()`, and rollback then has to
     * work out what a bare string was meant to be. This records the answer at the one point it is still
     * known. It is the `Class.getName()` rather than a tag of our own because the consumer compares it
     * against the `Class<*>` keys of `Block.configurations`, so there is no table to keep in sync.
     *
     * A class name is coupled to Mindustry's internals, so an engine release that renames or moves a
     * config class orphans every value written before it. That is safe by design and has to stay that
     * way: the consumer uses this only to put the named class first in the list it already searches, so
     * a stale, renamed or truncated value degrades to exactly the behaviour there was before this column
     * existed. Never turn it into a lookup that fails when the name does not resolve.
     *
     * 100 is comfortably wider than anything `Block.configurations` declares today, but H2 rejects an
     * over-long value rather than truncating it, and a rejected insert is requeued at the head of the
     * buffer and blocks every row behind it. The writer clamps rather than trusting this width.
     */
    val kind = varchar("kind", 100).nullable()

    /**
     * Mindustry uuid of the player who took the action, or null.
     *
     * [player] is a display name, which is a snapshot and is reusable over time, so matching a rollback
     * on it is ambiguous however exactly it is matched. Same width as `players.uuid`.
     */
    val uuid = varchar("uuid", 25).nullable()

    @OptIn(ExperimentalTime::class)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "ix_world_history_xy", isUnique = false, x, y)
    }
}