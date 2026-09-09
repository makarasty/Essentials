package essential.common.database

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlinx.coroutines.flow.toList

/**
 * Which database a test is actually talking to, and what indexes that database actually has.
 *
 * The suite runs in one JVM and only the first `loadGame(true)` of a run boots a database; every later
 * class inherits `TransactionManager.defaultDatabase` from whichever class ran before it. So a test
 * whose subject is a database-level guarantee - a unique index refusing a second insert - cannot assume
 * it is looking at the schema this build creates, and a green result that nobody has attributed to a
 * database is not evidence. These two make the failure message say which database answered.
 */

/** A one-line identity for the database this transaction would reach, for a failure message. */
internal fun reachedDatabase(): String {
    val db = TransactionManager.defaultDatabase
        ?: return "no database: TransactionManager.defaultDatabase is null"
    val dialect = runCatching { db.dialect.name }.getOrElse { "?" }
    // Every database here is opened from a ConnectionFactory rather than from a URL, so Exposed has
    // no URL to report for most of them and prints a placeholder. Say where it came from instead.
    val where = runCatching { db.url }.getOrNull()
        ?.takeUnless { it.isBlank() || it.contains("null://") }
        ?: "a connection factory this run opened (H2 lives at ./config/mods/Essentials/data/)"
    val shared = if (db === defaultDatabase) "" else " (NOT essential.common.database.defaultDatabase)"
    return "$dialect, $where$shared"
}

/**
 * The indexes [table] carries, read from the engine's own catalogue rather than from the Kotlin
 * `Table` object, which always reports what the code declares rather than what the server has.
 */
internal suspend fun indexesOn(table: String): List<String> = runCatching {
    val sql = when (TransactionManager.defaultDatabase?.dialect) {
        is PostgreSQLDialect ->
            "SELECT indexdef FROM pg_indexes WHERE tablename = '$table'"
        is MysqlDialect ->
            "SELECT CONCAT(index_name, IF(non_unique = 0, ' UNIQUE', ''), ' (', column_name, ')') " +
                "FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = '$table'"
        else ->
            "SELECT CONCAT(i.INDEX_NAME, ' ', i.INDEX_TYPE_NAME, ' (', c.COLUMN_NAME, ')') " +
                "FROM INFORMATION_SCHEMA.INDEXES i " +
                "JOIN INFORMATION_SCHEMA.INDEX_COLUMNS c " +
                "ON c.INDEX_NAME = i.INDEX_NAME AND c.TABLE_NAME = i.TABLE_NAME " +
                "AND c.INDEX_SCHEMA = i.INDEX_SCHEMA AND c.TABLE_SCHEMA = i.TABLE_SCHEMA " +
                "WHERE UPPER(i.TABLE_NAME) = UPPER('$table') " +
                "ORDER BY i.INDEX_NAME, c.ORDINAL_POSITION"
    }
    suspendTransaction {
        exec(sql, explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, String::class.java)
        }?.toList().orEmpty().filterNotNull()
    }
}.getOrElse { listOf("the index catalogue could not be read: ${it.message}") }
