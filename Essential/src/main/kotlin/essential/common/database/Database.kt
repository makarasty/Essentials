package essential.common.database

import arc.util.Log
import essential.common.bundle
import essential.common.database.data.getPluginData
import essential.common.database.data.mergePlayerAccounts
import essential.common.database.data.update
import essential.common.database.table.*
import essential.common.rootPath
import essential.core.Main
import io.asyncer.r2dbc.mysql.MySqlConnectionConfiguration
import io.asyncer.r2dbc.mysql.MySqlConnectionFactory
import io.r2dbc.h2.H2ConnectionConfiguration
import io.r2dbc.h2.H2ConnectionFactory
import io.r2dbc.h2.H2ConnectionOption
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ValidationDepth
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.vendors.*
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.mariadb.r2dbc.MariadbConnectionConfiguration
import org.mariadb.r2dbc.MariadbConnectionFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID

/** Holds [thisServerId]; delete it to re-key this server, and copy it if you clone the install. */
private const val SERVER_ID_FILE = "data/server-id"

/**
 * Which of the servers sharing this database is this one.
 *
 * `players` and `player_banned` record facts about one server in tables all six read, so a row that
 * means "mine" has to say whose. Written once to [SERVER_ID_FILE] and read from there afterwards:
 * deriving it from the configuration or the host would silently re-key this server the first time an
 * operator sets `plugin.serverId`, renames the host or moves the port, orphaning every row it had
 * already claimed. `plugin.serverId` seeds the file when it is set, so the stored value is readable
 * rather than opaque, but it is the file that decides from then on.
 */
internal val thisServerId: String by lazy {
    val file = rootPath.child(SERVER_ID_FILE)
    val stored = runCatching { if (file.exists()) file.readString().trim() else "" }.getOrDefault("")
    if (stored.isNotBlank()) return@lazy stored.take(100)

    val fresh = Main.conf.plugin.serverId.ifBlank { UUID.randomUUID().toString() }.take(100)
    runCatching {
        file.parent().mkdirs()
        file.writeString(fresh, false)
    }.onFailure {
        // A new identity every start is still safe - it claims nothing and releases nothing - but the
        // operator should know why the stale-connection release below never finds anything.
        Log.warn("[Database] could not store this server's identity in $SERVER_ID_FILE: ${it.message}")
    }
    fresh
}

var worldHistoryConnectionPool: ConnectionPool? = null
var defaultConnectionPool: ConnectionPool? = null
var worldHistoryDatabase: R2dbcDatabase? = null
var defaultDatabase: R2dbcDatabase? = null

/** Initial database setup */
suspend fun databaseInit(r2dbcUrl: String, user: String, pass: String) {
    // Parse database URL to determine the database type
    val databaseType = when {
        r2dbcUrl.startsWith("h2:") -> "h2"
        r2dbcUrl.startsWith("postgresql:") -> "postgresql"
        r2dbcUrl.startsWith("mysql:") -> "mysql"
        r2dbcUrl.startsWith("mariadb:") -> "mariadb"
        else -> "h2" // Default to H2
    }

    val h2HistoryFactory = h2("worldHistory")

    // One steady writer (the WorldHistoryBuffer flush loop), plus readers that do not queue behind it:
    // a /rollback and every tap of an admin in history view. Twenty was headroom nothing used; six
    // leaves room for a rollback and a few viewers at once without anyone waiting on the pool.
    val h2PoolConfig = ConnectionPoolConfiguration.builder(h2HistoryFactory.first)
        .maxSize(6)
        .initialSize(1)
        .maxIdleTime(Duration.ofMinutes(10))
        .maxAcquireTime(Duration.ofSeconds(10))
        .maxCreateConnectionTime(Duration.ofSeconds(5))
        .maxLifeTime(Duration.ofMinutes(30))
        .validationQuery("SELECT 1")
        .validationDepth(ValidationDepth.LOCAL)
        .build()

    val h2Pool = ConnectionPool(h2PoolConfig)
    worldHistoryConnectionPool = h2Pool
    worldHistoryDatabase = connectDatabase(h2Pool, h2HistoryFactory.second)

    rootPath.child("data").mkdirs()

    worldHistoryDatabase?.let { db ->
        suspendTransaction(db = db) {
            SchemaUtils.create(WorldHistoryTable)
        }
        migrateWorldHistoryColumns(db)
    }

    val (connectionFactory, dialect) = when (databaseType) {
        "postgresql" -> postgresql(r2dbcUrl, user, pass)
        "mysql" -> mysql(r2dbcUrl, user, pass)
        "mariadb" -> mariadb(r2dbcUrl, user, pass)
        else -> h2("database")
    }

    val poolConfig = ConnectionPoolConfiguration.builder(connectionFactory)
        .maxSize(5)
        .initialSize(2)
        .maxIdleTime(Duration.ofMinutes(10))
        .maxAcquireTime(Duration.ofSeconds(10))
        .maxCreateConnectionTime(Duration.ofSeconds(5))
        .maxLifeTime(Duration.ofMinutes(10))
        .validationQuery("SELECT 1")
        .validationDepth(ValidationDepth.REMOTE)
        .build()

    val pool = ConnectionPool(poolConfig)
    defaultConnectionPool = pool
    defaultDatabase = connectDatabase(pool, dialect)

    TransactionManager.defaultDatabase = defaultDatabase!!

    val legacyUpgrade = upgradeLegacyDatabase()

    val tablesToCreate = listOf(
        PlayerTable,
        PluginTable,
        PlayerBannedTable,
        AchievementTable,
        ContributionTable,
        MapRatingTable,
        ServerRoutingTable
    )

    // One table per call, because as a single call it is all or nothing: one table this build cannot
    // create beside a legacy one - a foreign key whose two sides disagree on width, say - took the other
    // six down with it and ended the boot. Each in its own transaction, since on PostgreSQL a failed
    // statement poisons the transaction it is in.
    //
    // The order of the list is now load bearing. The batched call sorted these by their references; one
    // table at a time does not, so a parent has to be declared ahead of anything pointing at it.
    // PlayerTable is first because AchievementTable and ContributionTable both reference it.
    val existingTables = tablesToCreate.filter { table ->
        runCatching { suspendTransaction { SchemaUtils.create(table) } }
            .onFailure { Log.err("[Database] could not create ${table.tableName}: ${it.message}") }
            .isSuccess
    }

    // Only the tables that are actually there: asking Exposed which columns a missing table is short of
    // throws out of the metadata read, which would undo the whole point of surviving the create above.
    //
    // statementsRequiredToActualizeScheme rather than addMissingColumnsStatements, which is what this
    // used to ask. The two differ by exactly the indexes and foreign keys, and measured against a real
    // MariaDB holding a schema the v4 script built, addMissingColumnsStatements offers none of them -
    // so the CONSTRAINT/INDEX filter below removed nothing and the difference between the live schema
    // and the Kotlin tables stayed invisible. The wider call is asked here so that difference can be
    // named; what is executed is still only the column half.
    val offered = runCatching {
        suspendTransaction {
            SchemaUtils.statementsRequiredToActualizeScheme(*existingTables.toTypedArray(), withLogs = false)
        }
    }.onFailure {
        Log.err("[Database] could not work out what the schema is missing: ${it.message}")
    }.getOrDefault(emptyList())

    // Constraints and indexes are declined rather than run, and now said out loud.
    //
    // They stay declined because adding one to a live table is not a repair the plugin can make
    // safely: a unique index over a column that already holds duplicates fails the ALTER and would
    // take the boot with it, and a legacy schema built by resources/sql - which carries far fewer
    // unique indexes than the Kotlin tables declare - is exactly where duplicates would have
    // accumulated. Whether they exist is something only somebody who can query the live data knows.
    //
    // So this is the report rather than the repair. Every line names one difference between what the
    // Kotlin tables declare and what the six servers are actually running, on the first boot, without
    // anyone having to know to go and run SHOW INDEX.
    val (declined, repairStatements) = offered.partition { statement ->
        listOf("CONSTRAINT", "INDEX").any { statement.contains(it, ignoreCase = true) }
    }

    for (statement in declined) {
        // Longest match, not first: a foreign key on player_achievements names players in its
        // REFERENCES clause, and reporting that repair against players sends the operator to the
        // wrong table.
        val table = existingTables
            .filter { statement.contains(it.tableName, ignoreCase = true) }
            .maxByOrNull { it.tableName.length }
            ?.tableName
        Log.warn("[Database/schema] repair declined on ${table ?: "an unrecognised table"}: $statement")
    }

    // A repair statement the engine refuses used to leave databaseInit, which stops the plugin loading
    // at all. Every statement here is a repair, so the schema without it is the one this server was
    // already running on, and logging the refusal is the smaller of the two failures. The column that
    // did it was plugin_data.id: v4.sql left it keyless, so the auto-increment Exposed offers for it is
    // one no MySQL or MariaDB will accept.
    for (statement in repairStatements) {
        Log.info("[Database] $statement")
        runCatching { suspendTransaction { exec(statement) } }.onFailure {
            Log.err("[Database] schema repair refused: $statement: ${it.message}")
        }
    }

    reshapeMapRatingIndex()

    releaseOwnStaleConnections()

    // With baselineVersion("5") Flyway's answer here is the constant 5 whether it migrated anything,
    // baselined an untouched schema, or found one another server had baselined, so feeding it into
    // updatePluginVersion marked a legacy upgrade that had just aborted as done and every later start
    // skipped the legacy path. plugin_data.database_version is now written only by the legacy upgrade.
    //
    // Skipped outright when that upgrade did not finish. Flyway would baseline the half-migrated schema
    // at 5 and run V6 onwards over it, and V8 needs the account columns the legacy scripts add.
    if (legacyUpgrade.failure == null) {
        mergeDuplicateAccounts()
        runFlywayMigration(databaseType, r2dbcUrl, user, pass)
    }

    reportLegacyUpgradeOutcome(legacyUpgrade)
}

/**
 * Adds to an existing `world_history` any column [WorldHistoryTable] has gained since it was created.
 *
 * The world-history database is not the shared one. It is a local per-server H2 file, created by exactly
 * one statement - `SchemaUtils.create(WorldHistoryTable)` above, which **skips a table that already
 * exists** - and the boot repair further down runs over the default database's seven tables and has never
 * included this one. So until this function existed a column added to the Kotlin table appeared only on a
 * server that had never booted, and on every other one every insert failed against the old shape. Two
 * findings wanting a column on this table were refused for that reason before it was built.
 *
 * Deliberately not Flyway, though Flyway is shipped and already wired up for the shared database:
 *
 * - the migration module is **optional** (`-PexcludeModules=migration`, and `services` expands to it),
 *   and excluding it strips `FlywayMigration`, the `db/migration` resources and `org/flywaydb/` from the
 *   artifact - a glob is spelled out here because `/` followed by two stars inside a KDoc opens a nested
 *   block comment, which Kotlin allows and which swallowed the rest of this file once already.
 *   A column that arrives only through Flyway is a column a modular jar does not have, while the code
 *   that writes it is in `common` and `core` and is always present - which is the original failure again,
 *   somewhere harder to find. This ships wherever the table does, so the write path may assume it;
 * - `runFlywayMigration` runs long after `SchemaUtils.create`, so on a fresh server a versioned
 *   `ALTER TABLE ... ADD COLUMN` would meet a table that already has the column, fail, log at error
 *   level and leave a failed row in `flyway_schema_history` that blocks every later migration in that
 *   file until somebody runs `repair` by hand on six machines.
 *
 * Asked of the engine rather than written out as DDL literals, so it cannot drift out of step with
 * [WorldHistoryTable] and the next column added there needs no edit here.
 *
 * **Additions only, and that filter is not decoration.** `addMissingColumnsStatements` is *not* the call
 * the shared repair below makes - that one asks `statementsRequiredToActualizeScheme` - and it is not
 * additive by construction either. Read out of `SchemaUtilityApi.mapMissingColumnStatementsTo`, it
 * appends `Column.ddl` for a genuinely missing column, **and** `Column.modifyStatements` for an existing
 * column whose type, nullability or default differs from what the engine's metadata reports, **and**
 * `Index.createStatement` for an index touching a missing column, **and** a primary-key statement when
 * the existing key differs. On H2 a spurious modify renders as `ALTER COLUMN` and would therefore
 * *succeed* - rewriting a live column on six per-server files, once per boot, forever, with nothing to
 * refuse it and nothing to notice. So everything that is not an addition is reported and **not run**,
 * which is the same policy and the same reasoning as the shared repair's `CONSTRAINT`/`INDEX` partition
 * further down: this is the report rather than the repair, because whether a live column can safely be
 * rewritten is something only somebody who can look at the data knows.
 */
private suspend fun migrateWorldHistoryColumns(db: R2dbcDatabase) {
    // Log.warn, not err: "I could not tell what is missing" is not "I failed to repair it", and a boot
    // that cannot read the metadata still has a working table for every column that was already there.
    val offered = runCatching {
        suspendTransaction(db = db) {
            SchemaUtils.addMissingColumnsStatements(WorldHistoryTable, withLogs = false)
        }
    }.onFailure {
        Log.warn("[Database/worldHistory] could not work out what world_history is missing: ${it.message}")
    }.getOrDefault(emptyList())

    // Exposed renders an added column as `ALTER TABLE <t> ADD <ddl>` - `ADD`, and not `ADD COLUMN`. The
    // two negative clauses are what stop the predicate calling something an addition when it is not one:
    // a modify is `ALTER COLUMN` on H2, and a column that closes a primary key carries `, ADD <pk
    // constraint>` appended to its own addition.
    val (additions, declined) = offered.partition { statement ->
        statement.contains(" ADD ", ignoreCase = true) &&
            !statement.contains(" ALTER COLUMN ", ignoreCase = true) &&
            !statement.contains("CONSTRAINT", ignoreCase = true) &&
            !statement.contains("INDEX", ignoreCase = true)
    }

    for (statement in declined) {
        Log.warn("[Database/worldHistory] repair declined, this is a report and not a repair: $statement")
    }

    for (statement in additions) {
        Log.info("[Database/worldHistory] $statement")
        // Log.err, unlike the declined half, because a missing column really is not survivable: the code
        // that writes it ships whatever happens to this call, so every later insert fails and rollback
        // stops recording anything. Each statement gets its own transaction so one failure does not take
        // the others - though under the test harness's error guard this line throws and ends the boot,
        // which is a property the shared repair's own Log.err already has.
        runCatching { suspendTransaction(db = db) { exec(statement) } }.onFailure {
            Log.err("[Database/worldHistory] column repair refused: $statement: ${it.message}")
        }
    }
}

/**
 * Map ratings used to carry a unique index on player_uuid alone, which meant one rating per
 * player for all maps; the table now declares (player_uuid, map_name). SchemaUtils only
 * creates indexes for new tables, so an existing database gets the swap here. Every statement
 * runs in its own transaction: on PostgreSQL a failed statement poisons the transaction it is
 * in, and both statements are allowed to fail - the old index may be gone already, the new
 * one may already exist.
 */
private suspend fun reshapeMapRatingIndex() {
    val dropOld = when (defaultDatabase?.config?.explicitDialect) {
        is MysqlDialect -> "ALTER TABLE map_ratings DROP INDEX map_ratings_player_uuid_unique"
        else -> "ALTER TABLE map_ratings DROP CONSTRAINT IF EXISTS map_ratings_player_uuid_unique"
    }
    val createNew = "CREATE UNIQUE INDEX map_ratings_player_uuid_map_name_unique ON map_ratings (player_uuid, map_name)"
    for (statement in listOf(dropOld, createNew)) {
        runCatching { suspendTransaction { exec(statement) } }
    }
}

/**
 * Clears `is_connected` on the rows this server itself left connected.
 *
 * Every writer of that column runs on the server the player is on, so a server killed without running
 * its dispose listener leaves the column true for good and `/login` then refuses that account on all
 * six. Only the rows carrying this server's own [thisServerId] are cleared: clearing the column
 * outright would mark offline every player online on the other five, and a row written before the
 * column existed carries null and belongs to nobody, so it is left alone rather than claimed.
 */
private suspend fun releaseOwnStaleConnections() {
    runCatching {
        suspendTransaction {
            val released = PlayerTable.update({
                (PlayerTable.connectedServer eq thisServerId) and (PlayerTable.isConnected eq true)
            }) {
                it[PlayerTable.isConnected] = false
                it[PlayerTable.connectedServer] = null
            }
            val unowned = PlayerTable.select(PlayerTable.id)
                .where { PlayerTable.connectedServer.isNull() and (PlayerTable.isConnected eq true) }
                .count()
            released to unowned
        }
    }.onSuccess { (released, unowned) ->
        if (released > 0) Log.info("Released $released account(s) left connected by a previous start")
        if (unowned > 0) Log.warn(
            "$unowned account(s) are marked connected by a server that predates the connected_server " +
                "column, and /login will refuse them. With every server stopped, clear them with: " +
                "UPDATE players SET is_connected = false WHERE connected_server IS NULL"
        )
    }.onFailure {
        // Log.warn rather than Log.err: a boot whose schema repair was refused reaches this with no
        // connected_server column, and the plugin failing to load is the larger of the two failures.
        Log.warn("[Database] could not release the accounts left connected by a previous start: ${it.message}")
    }
}

/**
 * Folds every account that owns more than one `players` row into its newest row, ahead of Flyway V8.
 *
 * V8 puts a unique index on `account_id` and, to get there, deletes every duplicate but the newest along
 * with its achievements and contributions. Merging them first through [mergePlayerAccounts] keeps the
 * stats and achievements, and leaves V8 only the index to add. Newest is V8's own order - latest login,
 * then highest id - so both pick the same survivor.
 *
 * The duplicate accounts are asked of the database rather than grouped here, so the engine's collation
 * decides what counts as the same account, exactly as it will for the unique index. Once V8 has run the
 * index makes the grouping query return nothing, so on every later boot this is one cheap read.
 */
internal suspend fun mergeDuplicateAccounts() {
    val duplicated = runCatching {
        suspendTransaction {
            PlayerTable.select(PlayerTable.accountID)
                .where { PlayerTable.accountID.isNotNull() and (PlayerTable.accountID neq "") }
                .groupBy(PlayerTable.accountID)
                .having { PlayerTable.id.count() greater 1L }
                .mapNotNull { it[PlayerTable.accountID] }
                .toList()
        }
    }.onFailure {
        Log.warn("[Database] could not look for duplicate accounts: ${it.message}")
    }.getOrDefault(emptyList())

    for (account in duplicated) {
        val rows = suspendTransaction {
            PlayerTable.select(PlayerTable.id, PlayerTable.uuid, PlayerTable.lastLoginDate)
                .where { PlayerTable.accountID eq account }
                .map { Triple(it[PlayerTable.id], it[PlayerTable.uuid], it[PlayerTable.lastLoginDate]) }
                .toList()
        }.sortedWith(compareByDescending<Triple<UInt, String, LocalDateTime>> { it.third }.thenByDescending { it.first })

        val keep = rows.firstOrNull() ?: continue
        for (older in rows.drop(1)) {
            runCatching { mergePlayerAccounts(older.second, keep.second) }
                .onSuccess { Log.info("[Database] duplicate account '$account': $it") }
                .onFailure { Log.warn("[Database] could not merge duplicate account '$account' (${older.second}): ${it.message}") }
        }
    }
}

/**
 * Execute Flyway migrations
 */
fun runFlywayMigration(
    databaseType: String,
    r2dbcUrl: String,
    user: String,
    pass: String,
    baselineVersion: String = "5",
    targetVersion: String? = null,
): String? {
    return try {
        val migrationClass = Class.forName("essential.core.service.migration.FlywayMigration")
        migrationClass.getMethod(
            "migrate",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        ).invoke(null, databaseType, r2dbcUrl, user, pass, baselineVersion, targetVersion) as? String
    } catch (_: ClassNotFoundException) {
        Log.info("Flyway migration module is not included; database migration was skipped.")
        null
    } catch (e: ReflectiveOperationException) {
        Log.err("Failed to invoke the Flyway migration module", e)
        null
    } catch (e: LinkageError) {
        Log.err("Flyway migration module dependencies are unavailable", e)
        null
    }
}

/**
 * Migration for player achievements from status column to player_achievements table
 */
private suspend fun migrateStatusToAchievements() {
    val playerTable = object : Table("players") {
        val id = uinteger("id")
        val status = text("status")
    }

    val achievementTable = object : Table("player_achievements") {
        val id = uinteger("id").autoIncrement()
        val playerId = uinteger("player_id")
        val achievementName = varchar("achievement_name", 100)
        val completedAt = datetime("completed_at")
        override val primaryKey = PrimaryKey(id)
    }

    try {
        suspendTransaction {
            playerTable.selectAll().map { row ->
                row[playerTable.id] to row[playerTable.status]
            }.toList().forEach { (playerId, statusStr) ->
                if (statusStr.isNotEmpty() && statusStr != "{}") {
                    try {
                        val json = Json.parseToJsonElement(statusStr).jsonObject
                        val achievements = json.filter { it.key.startsWith("achievement.") }

                        if (achievements.isNotEmpty()) {
                            achievements.forEach { (key, value) ->
                                val name = key.removePrefix("achievement.")
                                val dateStr = value.jsonPrimitive.content

                                val exists = achievementTable.selectAll()
                                    .where { (achievementTable.playerId eq playerId) and (achievementTable.achievementName eq name) }
                                    .firstOrNull() != null

                                if (!exists) {
                                    achievementTable.insert {
                                        it[achievementTable.playerId] = playerId
                                        it[achievementTable.achievementName] = name
                                        try {
                                            it[achievementTable.completedAt] = LocalDateTime.parse(dateStr)
                                        } catch (_: Exception) {
                                            // Keep default (CurrentDateTime)
                                        }
                                    }
                                }
                            }

                            val newStatus = json.filter { !it.key.startsWith("achievement.") }
                            playerTable.update({ playerTable.id eq playerId }) {
                                it[playerTable.status] = JsonObject(newStatus).toString()
                            }
                        }
                    } catch (e: Exception) {
                        Log.warn("Failed to parse status for player $playerId: ${e.message}")
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.err("Achievement migration failed: ${e.message}")
        e.printStackTrace()
    }
}

private fun connectDatabase(factory: ConnectionFactory, dialect: DatabaseDialect): R2dbcDatabase {
    return R2dbcDatabase.connect(
        connectionFactory = factory,
        databaseConfig = R2dbcDatabaseConfig {
            defaultMaxAttempts = 1
            defaultMinRetryDelay = 0
            defaultMaxRetryDelay = 0
            explicitDialect = dialect
        }
    )
}

private suspend fun updatePluginVersion(version: UByte) {
    val data = getPluginData()
    if (data != null) {
        data.databaseVersion = version
        data.update()
    }
}

/**
 * The legacy upgrade script for one version step.
 *
 * They sit beside the Flyway migrations, one folder per engine family, since upstream moved them there;
 * MariaDB shares the MySQL folder. Flyway never runs them itself: it always baselines at
 * [LEGACY_BASELINE_VERSION], which puts V4 and V5 below its baseline, and [upgradeLegacyDatabase] runs
 * them statement by statement instead, because they were written for a runner that survives a statement
 * failing and Flyway abandons the whole script on the first one.
 */
internal fun legacyScriptPath(version: UByte, dialect: DatabaseDialect?): String {
    val profile = when (dialect) {
        is H2Dialect -> "h2"
        is PostgreSQLDialect -> "postgres"
        else -> "mysql"
    }
    return "db/migration/$profile/V${version}__legacy_migrate_$profile.sql"
}

internal const val LEGACY_BASELINE_VERSION: UByte = 5u

/**
 * Runs [body] between a savepoint and its release, and hands back what it threw rather than throwing.
 *
 * Only PostgreSQL takes the savepoint, because only PostgreSQL aborts a transaction on a failed
 * statement - and the whole-script rollback a critical failure relies on is that same behaviour, which
 * is why the repair had to be a savepoint rather than a transaction per statement. MySQL, MariaDB and
 * H2 implicitly commit at every DDL statement, so they have no whole-script rollback to protect and
 * they drop every savepoint the moment one of those runs. H2 has no `RELEASE SAVEPOINT` at all.
 *
 * The release runs on the failing path too: `ROLLBACK TO SAVEPOINT` does not destroy the savepoint, and
 * a script that leaves more than 64 of them live costs every snapshot the backend takes afterwards.
 */
private suspend fun R2dbcTransaction.withSavepoint(name: String, body: suspend () -> Unit): Throwable? {
    val poisons = db.dialect is PostgreSQLDialect
    if (poisons) exec("SAVEPOINT $name")
    val failure = runCatching { body() }.exceptionOrNull()
    if (poisons) {
        if (failure != null) {
            // Logged rather than swallowed: a rollback that did not happen leaves the transaction
            // aborted, and then the next statement fails for a reason that has nothing to do with it.
            runCatching { exec("ROLLBACK TO SAVEPOINT $name") }.onFailure {
                Log.warn("Could not roll back to $name, so the rest of this upgrade will fail: ${it.message}")
            }
        }
        runCatching { exec("RELEASE SAVEPOINT $name") }
    }
    return failure
}

/**
 * Runs one legacy upgrade script inside the caller's transaction, a statement at a time, and reports
 * how many statements it swallowed.
 *
 *
 * A failure that is not critical is rolled back to its own savepoint and the script carries on; a
 * critical one is rethrown, and the caller's transaction takes the whole script back with it. Without
 * the savepoint a swallowed failure poisoned the transaction the rest of the script shares - on
 * PostgreSQL every later statement answers `25P02 current transaction is aborted`, so the upgrade died
 * several statements away from its cause and left the version stamp behind.
 *
 * This is the third place in this file that has to say a failed statement poisons the transaction on
 * PostgreSQL - see [reshapeMapRatingIndex] and the per-table create loop in [databaseInit].
 *
 * Savepoint names are per call, not per transaction, so two scripts applied in one transaction would
 * shadow each other's. Nothing does: [upgradeLegacyDatabase] opens one transaction per script.
 */
internal suspend fun R2dbcTransaction.applyLegacyScript(script: String): List<String> {
    val swallowed = mutableListOf<String>()
    script.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, statement ->
        val failure = withSavepoint("essential_upgrade_$index") { exec(statement) }
        if (failure != null) {
            // A substring test over SQL, so a statement merely mentioning either word - in a column
            // name, a string literal, a REFERENCES clause - is classed critical too. The marker is
            // named in the message because an operator reading a first boot has to be able to tell a
            // migration that genuinely failed from the classifier firing on a word.
            val marker = listOf("plugin_data", "players").firstOrNull { statement.contains(it, true) }
            if (marker != null) {
                throw IllegalStateException(
                    "Critical statement failed: $statement (classed critical because its text " +
                        "contains \"$marker\")",
                    failure
                )
            }
            Log.warn("Failed to execute statement: $statement. Reason: ${failure.message}")
            swallowed += statement
        }
    }
    return swallowed
}

/**
 * The table a legacy statement acts on, for reporting only.
 *
 * Deliberately not used to decide what counts as critical. That decision is a substring test today, and
 * replacing it changes which failures abort an upgrade that is about to run on live data for the first
 * time - a behaviour change, not a repair, and not one to make in the same run as the report that would
 * let somebody judge it.
 */
private val LEGACY_STATEMENT_TABLE = Regex(
    """(?i)\b(?:ALTER\s+TABLE|UPDATE|INSERT\s+INTO|DELETE\s+FROM|DROP\s+TABLE|CREATE\s+TABLE)\s+(?:IF\s+(?:NOT\s+)?EXISTS\s+)?`?(\w+)`?"""
)

private fun tableNamedIn(statement: String): String? =
    LEGACY_STATEMENT_TABLE.find(statement)?.groupValues?.getOrNull(1)

/**
 * What the legacy upgrade did, in the two terms that decide whether a boot is trustworthy.
 *
 * [swallowed] matters as much as [failure]. Only a statement naming `players` or `plugin_data` is
 * treated as critical, and ten statements across `v4.sql` and `v5.sql` name neither - among them both
 * legacy-ban migrations and every `map_ratings` statement in v5. Any of those failing leaves the
 * version stamped as done over a schema that is missing whatever they were carrying.
 */
private class LegacyUpgradeOutcome(val failure: Throwable?, val swallowed: List<String>) {
    /** Named so the deploy check is one line to read rather than five statements to compare by eye. */
    val skippedTables: String
        get() = swallowed.mapNotNull(::tableNamedIn).distinct().sorted()
            .joinToString().ifEmpty { "unrecognised" }
}

private suspend fun upgradeLegacyDatabase(): LegacyUpgradeOutcome {
    val swallowed = mutableListOf<String>()
    try {
        var currentVersion: UByte?

        val candidates = listOf(
            Paths.get("config/mods/Essentials/data/database.mv.db").toFile(),
            rootPath.child("data/database.mv.db").file(),
        )
        // The file on disk only says something about an H2 setup. A server moved to
        // MySQL keeps its old .mv.db lying around, and treating that as "legacy version 3"
        // sends H2 migration scripts into the new database.
        val found = if (defaultDatabase?.config?.explicitDialect is H2Dialect) {
            candidates.firstOrNull { it.exists() }
        } else {
            null
        }
        currentVersion = try {
            getPluginData()?.databaseVersion
        } catch (_: Throwable) {
            val dbExists = try {
                suspendTransaction {
                    val dialect = defaultDatabase!!.config.explicitDialect
                    val query = when {
                        dialect is PostgreSQLDialect -> "SELECT 1 FROM public.db LIMIT 1"
                        else -> "SELECT 1 FROM db LIMIT 1"
                    }
                    exec(query)
                    true
                }
            } catch (_: Throwable) {
                false
            }
            if (dbExists) 3u else null
        }

        if (currentVersion == null) {
            return LegacyUpgradeOutcome(null, swallowed)
        }

        // Zero is not a legacy version: only createPluginData() ever wrote it, on a database this build
        // had just created at the current shape. The legacy scripts start at v4 and rename tables such
        // a database does not have, so running them would fail on every start.
        if (currentVersion == 0u.toUByte()) {
            updatePluginVersion(LEGACY_BASELINE_VERSION)
            return LegacyUpgradeOutcome(null, swallowed)
        }

        if (currentVersion < LEGACY_BASELINE_VERSION) {
            Log.info(bundle["database.upgrade.start", currentVersion, LEGACY_BASELINE_VERSION])

            for (v in (currentVersion.toUInt() + 1u)..LEGACY_BASELINE_VERSION.toUInt()) {
                val version = v.toUByte()
                val sqlFile = legacyScriptPath(version, defaultDatabase!!.config.explicitDialect)

                // A version step with no script of its own is not a step that succeeded. It used to be
                // silent: the loop simply advanced, and the stamp below then said the database had
                // reached the baseline over an upgrade that had never run.
                val sqlScript = Main::class.java.classLoader.getResourceAsStream(sqlFile)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException(
                        "No upgrade script for version $version at $sqlFile (a build without the migration module ships none)"
                    )
                Log.info(bundle["database.upgrade.execute", sqlFile])

                suspendTransaction {
                    swallowed += applyLegacyScript(sqlScript)

                    updatePluginVersion(version)
                }

                // Outside the transaction above, not inside it. This opens its own and swallows every
                // failure it meets, so within the script's transaction a failure it swallowed left that
                // transaction aborted on PostgreSQL - after the version stamp had already been written,
                // and with COMMIT answering an aborted transaction by rolling it back silently. The step
                // would then report success having applied nothing.
                if (version == 4u.toUByte()) {
                    migrateStatusToAchievements()
                }
            }

            updatePluginVersion(LEGACY_BASELINE_VERSION)
            Log.info(bundle["database.upgrade.end"])
        }
        return LegacyUpgradeOutcome(null, swallowed)
    } catch (e: Exception) {
        // Deliberate: an upgrade that did not reach its end has to be retried on the next start, on
        // this server and on every other one sharing the row.
        Log.warn("Legacy database upgrade did not finish, plugin_data.database_version was left unchanged: ${e.message}")
        e.printStackTrace()
        // Handed back rather than only logged. databaseInit carries on regardless - see
        // reportLegacyUpgradeOutcome for why, and for what the operator gets to read instead.
        return LegacyUpgradeOutcome(e, swallowed)
    }
}

/**
 * Says in the boot log whether the legacy upgrade finished, because until now nothing did.
 *
 * A critical statement failing inside a v4 or v5 script throws out of [applyLegacyScript],
 * [upgradeLegacyDatabase] catches it so the version stamp is left where it was - which is right - and
 * [databaseInit] then carried on into `SchemaUtils.create` and Flyway exactly as though the upgrade had
 * succeeded. The server came up on a half-migrated schema and the only thing that said so was one
 * warning several hundred lines earlier in a startup log.
 *
 * Whether such a boot should be refused outright is the operator's call and is filed separately; this
 * makes the state readable either way, on the first boot, without knowing what to grep for.
 *
 * The version is read back here rather than remembered from the upgrade: what an operator needs is the
 * number actually in the row on the database all six servers share.
 */
private suspend fun reportLegacyUpgradeOutcome(outcome: LegacyUpgradeOutcome) {
    val read = runCatching { getPluginData()?.databaseVersion }
    val stored = read.getOrNull()
    val version = when {
        stored != null -> stored.toString()
        // A row that is simply not there yet is not the same as a table that cannot be read, and on a
        // first-ever boot it is the ordinary case: createPluginData runs after databaseInit.
        read.isSuccess -> "not written yet"
        else -> "unreadable"
    }

    // The stamp is checked as well as the failure, because the two are not the same question:
    // updatePluginVersion writes nothing at all when there is no plugin_data row to write to, and
    // reports that to nobody. A first-ever boot legitimately has no row - createPluginData runs after
    // databaseInit and stamps the baseline itself - so a successful read that comes back empty counts
    // as reaching it.
    val reachedBaseline = stored == LEGACY_BASELINE_VERSION || (read.isSuccess && stored == null)

    if (outcome.failure == null && reachedBaseline) {
        // Swallowed statements are reported separately rather than folded into the block below.
        // applyLegacyScript only rethrows for statements naming players or plugin_data, so a script
        // can reach its end having skipped real work - and it is not the same event as an upgrade that
        // stopped. Measured against a real MariaDB, v5.sql skips all five of its map_ratings
        // statements on every run, because that table is created by SchemaUtils after this point and
        // never by the scripts. Reporting that as an abort would teach an operator to ignore the line
        // that matters.
        if (outcome.swallowed.isEmpty()) {
            Log.info("[Database/upgrade] schema version is $version, no legacy upgrade is outstanding")
        } else {
            Log.warn(
                "[Database/upgrade] schema version is $version, but ${outcome.swallowed.size} " +
                    "statement(s) failed and were skipped as non-critical (tables: " +
                    "${outcome.skippedTables}) - the \"Failed to execute statement\" lines above name " +
                    "them, and whatever they were carrying is not in this schema"
            )
        }
        return
    }

    val reason = outcome.failure
        ?.let { "it stopped on: ${it.message}" }
        ?: "the version stamp was never written, so the next start will run the whole upgrade again"

    Log.err("[Database/upgrade] ####################################################################")
    Log.err("[Database/upgrade] The legacy database upgrade DID NOT FINISH, and the server started anyway")
    Log.err("[Database/upgrade] on a schema part way between two versions.")
    Log.err("[Database/upgrade]   plugin_data.database_version still reads $version, target is $LEGACY_BASELINE_VERSION")
    Log.err("[Database/upgrade]   $reason")
    if (outcome.swallowed.isNotEmpty()) {
        Log.err(
            "[Database/upgrade]   ${outcome.swallowed.size} further statement(s) were skipped as " +
                "non-critical (tables: ${outcome.skippedTables})"
        )
    }
    Log.err("[Database/upgrade] Whatever is missing is missing on every server sharing this database,")
    Log.err("[Database/upgrade] and the upgrade is retried on the next start - so it will stop in the")
    Log.err("[Database/upgrade] same place until that statement is dealt with.")
    Log.err("[Database/upgrade] ####################################################################")
}

internal fun parseR2dbcUrl(r2dbcUrl: String, prefix: String, defaultPort: String): Triple<String, Int, String> {
    val url = r2dbcUrl.removePrefix(prefix)
    val host = url.substringBefore(":").substringBefore("/")
    val port = url.substringAfter(":", defaultPort).substringBefore("/")
    val database = url.substringAfter("/")
    return Triple(host, port.toInt(), database)
}

fun postgresql(r2dbcUrl: String, user: String, pass: String): Pair<ConnectionFactory, DatabaseDialect> {
    val (host, port, database) = parseR2dbcUrl(r2dbcUrl, "postgresql://", "5432")

    return Pair(
        PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(host)
                .port(port)
                .database(database)
                .username(user)
                .password(pass)
                .schema("public")
                .build()
        ), PostgreSQLDialect()
    )
}

fun h2(name: String): Pair<ConnectionFactory, DatabaseDialect> = Pair(
    H2ConnectionFactory(
        H2ConnectionConfiguration.builder()
            .url("./config/mods/Essentials/data/$name")
            .property(H2ConnectionOption.DB_CLOSE_DELAY, "-1")
            .property(H2ConnectionOption.DB_CLOSE_ON_EXIT, "FALSE")
            .property("DEFAULT_NULL_ORDERING", "HIGH")
            // Page cache in KB. H2's default is 16 MB per database and this opens two; both are read
            // by key or not at all during play, so a quarter of that holds the working set.
            .property("CACHE_SIZE", "4096")
            .username("sa")
            .password("123")
            .build()
    ),
    H2Dialect()
)

fun mysql(r2dbcUrl: String, user: String, pass: String): Pair<ConnectionFactory, DatabaseDialect> {
    val (host, port, database) = parseR2dbcUrl(r2dbcUrl, "mysql://", "3306")

    return Pair(
        MySqlConnectionFactory.from(
            MySqlConnectionConfiguration.builder()
                .host(host)
                .port(port)
                .database(database)
                .username(user)
                .password(pass)
                .build()
        ),
        MysqlDialect()
    )
}

fun mariadb(r2dbcUrl: String, user: String, pass: String): Pair<ConnectionFactory, DatabaseDialect> {
    val (host, port, database) = parseR2dbcUrl(r2dbcUrl, "mariadb://", "3306")

    return Pair(
        MariadbConnectionFactory.from(
            MariadbConnectionConfiguration.builder()
                .host(host)
                .port(port)
                .database(database)
                .username(user)
                .password(pass)
                .build()
        ),
        MariaDBDialect()
    )
}

fun databaseClose() {
    runBlocking {
        try {
            defaultConnectionPool?.dispose()
        } catch (_: Throwable) {}
        defaultConnectionPool = null
        defaultDatabase = null

        try {
            worldHistoryConnectionPool?.dispose()
        } catch (_: Throwable) {}
        worldHistoryConnectionPool = null
        worldHistoryDatabase = null
    }
}
