package essential.common.database

import PluginTest.Companion.loadGame
import PluginTest.Companion.stopPlugin
import arc.util.Log
import essential.common.bundle
import essential.common.database.data.getPluginData
import essential.common.database.data.mergePlayerAccounts
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.MariaDBDialect
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.junit.Assume.assumeTrue
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boots [databaseInit] against a real MariaDB and a real MySQL server.
 *
 * The rest of the suite is H2-backed, and H2 accepts DDL these engines refuse: two defects that would
 * have stopped the plugin loading passed every test in this suite and were caught by a human reading
 * dialect code. These tests run the real engines instead. Each test runs against every server that
 * answers and is skipped by a JUnit assumption when none does, because this machine does not always
 * have one - a failure on absence would get the class deleted and the coverage with it.
 *
 * MariaDB is the engine the servers actually run, and it is the one whose schema came through the
 * `resources/sql` upgrade scripts rather than from `SchemaUtils`. MySQL is here because it is the other
 * engine `Database.kt` claims to support and it is stricter, so it fails first.
 *
 * Starting them (both left at their default `sql_mode`, which is where the strictness comes from):
 *
 *     mysqld  --initialize-insecure --datadir=D:/scratch/mysql/data
 *     mysqld  --datadir=D:/scratch/mysql/data --port=3399
 *     mariadb-install-db --datadir=D:/scratch/mariadb/data
 *     mariadbd --datadir=D:/scratch/mariadb/data --port=3398
 *     # then, on each: CREATE DATABASE essential_test CHARACTER SET utf8mb4;
 *     #                CREATE DATABASE essential_legacy CHARACTER SET utf8mb4;
 *
 * Both databases are dropped and recreated by these tests, so point them at scratch instances only.
 * Ports move with -Dessential.test.mariadb.port and -Dessential.test.mysql.port; the rest with
 * -Dessential.test.db.host / .user / .password / .name / .legacy.
 */
class LiveDatabaseBootTest {
    private val host = System.getProperty("essential.test.db.host", "127.0.0.1")
    private val user = System.getProperty("essential.test.db.user", "root")
    private val pass = System.getProperty("essential.test.db.password", "")
    private val testDb = System.getProperty("essential.test.db.name", "essential_test")
    private val legacyDb = System.getProperty("essential.test.db.legacy", "essential_legacy")

    /** The engine the production servers run comes first, so its failure is the one reported. */
    private val engines = listOf(
        Engine("MariaDB", "mariadb", System.getProperty("essential.test.mariadb.port", "3398")),
        Engine("MySQL", "mysql", System.getProperty("essential.test.mysql.port", "3399")),
    )

    private class Engine(val label: String, val scheme: String, val port: String) {
        override fun toString() = "$label on $port"
    }

    /** Written from r2dbc's own threads, so not an ordinary list. */
    private var bootLog = CopyOnWriteArrayList<String>()
    private var booted = false

    // MariaDB Connector/J speaks both protocols and is already on the test classpath through the flyway
    // bundle; FlywayMigration reaches a MySQL server over the same driver.
    private fun Engine.jdbc(database: String) =
        "jdbc:mariadb://$host:$port/$database?connectTimeout=3000&socketTimeout=30000"

    private fun Engine.open(database: String = ""): Connection =
        DriverManager.getConnection(jdbc(database), user, pass)

    private fun Engine.answers() =
        runCatching { open().use { it.createStatement().use { s -> s.execute("SELECT 1") } } }.isSuccess

    /**
     * Runs [body] once per reachable engine, and skips the test when none is. Each engine gets its own
     * teardown, so one engine's pools never outlive its turn.
     */
    private fun onEachEngine(body: (Engine) -> Unit) {
        val live = engines.filter { it.answers() }
        assumeTrue("no database server on ${engines.joinToString()}, skipping", live.isNotEmpty())
        for (engine in live) {
            try {
                body(engine)
            } finally {
                closeDatabase()
            }
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.execScript(script: String) =
        script.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }

    private fun Connection.column(table: String, column: String): Triple<String, String, String?>? =
        prepareStatement(
            "SELECT data_type, is_nullable, column_default FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?"
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, column)
            statement.executeQuery().use { rows ->
                if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getString(3)) else null
            }
        }

    private fun Connection.tables(): Set<String> =
        createStatement().use { statement ->
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
            ).use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1).lowercase()) }
            }
        }

    private fun Connection.scalar(sql: String): String? =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun Engine.reset(database: String) = open().use {
        it.exec("DROP DATABASE IF EXISTS `$database`")
        it.exec("CREATE DATABASE `$database` CHARACTER SET utf8mb4")
    }

    /**
     * One plugin boot against [database]. Every line the plugin logs lands in [bootLog], which is read
     * after the call rather than returned, because a boot that throws is the case that log is most
     * needed for.
     *
     * The log is the report: `Database.kt` prints each add-missing-columns statement before running it,
     * and the legacy upgrade prints the script it chose and every statement it swallowed.
     */
    private fun Engine.boot(database: String) {
        loadGame(deleteConfig = false)
        // The world-history H2 database is opened at a literal ./config path, which is where a real
        // server's data directory puts it but not where this suite's is.
        File("config/mods/Essentials/data").mkdirs()

        bootLog = CopyOnWriteArrayList()
        booted = true
        val previous = Log.logger
        // Replaced outright rather than chained: PluginTest's handler turns any Log.err into a thrown
        // RuntimeException, and databaseInit logs err on paths this class is here to reach.
        Log.logger = Log.LogHandler { _, text -> bootLog += text }
        try {
            runBlocking { databaseInit("$scheme://$host:$port/$database", user, pass) }
        } finally {
            Log.logger = previous
        }
    }

    private fun Engine.bootCatching(database: String): Throwable? =
        runCatching { boot(database) }.exceptionOrNull()

    private fun List<String>.swallowed() = filter { it.startsWith("Failed to execute statement:") }

    private fun List<String>.emittedDdl() = filter { it.startsWith(DDL_TAG) }.map { it.removePrefix(DDL_TAG) }

    private fun List<String>.declinedRepairs() = filter { it.startsWith(DECLINED_TAG) }

    private fun List<String>.report() = joinToString("\n").ifEmpty { "(nothing was logged)" }

    /** Everything the next person needs to place a failure: what ran, what was swallowed, what threw. */
    private fun diagnosis(engine: Engine, failure: Throwable?) = buildString {
        append("Engine: ").append(engine)
        append("\nBoot log:\n").append(bootLog.report())
        append("\nStatements the legacy upgrade swallowed:\n").append(bootLog.swallowed().report())
        append("\nDDL the boot ran unguarded:\n").append(bootLog.emittedDdl().report())
        if (failure != null) append("\ndatabaseInit threw:\n").append(failure.stackTraceToString())
    }

    /**
     * A skipped test still runs this, so it has to be inert unless this class actually booted: other
     * classes leave a loaded plugin behind, and closing its database out from under them would leave
     * every later transaction with no connection. [stopPlugin] rather than [databaseClose] because it
     * also clears the flag that makes the next `loadGame(true)` re-run `databaseInit`.
     */
    @AfterTest
    fun closeDatabase() {
        if (!booted) return
        booted = false
        // stopPlugin sends SHUTDOWN to every open database, which a real server obeys. Drop the
        // reference to it first so only the H2 world-history database gets that.
        defaultDatabase = null
        stopPlugin()
    }

    @Test
    fun aFreshDatabaseIsCreatedWholeAndNeedsNoAlterOnTheNextStart() = onEachEngine { engine ->
        engine.reset(testDb)

        val failure = engine.bootCatching(testDb)
        val why by lazy { diagnosis(engine, failure) }
        assertNull(failure, "a fresh database could not be created. $why")

        val dialect = defaultDatabase!!.config.explicitDialect
        val expected = if (engine.scheme == "mariadb") MariaDBDialect::class else MysqlDialect::class
        assertEquals(expected, dialect!!::class, "databaseInit chose the wrong dialect for $engine")

        engine.open(testDb).use { connection ->
            val tables = connection.tables()
            for (table in listOf(
                "players", "plugin_data", "player_banned", "player_achievements",
                "player_contributions", "map_ratings", "server_routing"
            )) {
                assertTrue(table in tables, "SchemaUtils.create left out $table. $why")
            }

            // These engines reject a literal default on a TEXT column with error 1101, which would have
            // taken the CREATE with it, so the column existing at all is the proof it stayed nullable.
            val statusData = connection.column("players", "status_data")
            assertNotNull(statusData, "players.status_data was not created. $why")
            assertEquals("text", statusData.first.lowercase(), "players.status_data is not a TEXT column")
            assertEquals("YES", statusData.second, "players.status_data was created NOT NULL")
        }

        // Database.kt runs addMissingColumnsStatements on every start and executes the result. A schema
        // this build has just created must produce none of them.
        assertEquals(emptyList<String>(), bootLog.emittedDdl(), "a database created moments ago needed repair. $why")

        closeDatabase()
        val second = engine.bootCatching(testDb)
        assertNull(second, "the second start of an unchanged database failed. ${diagnosis(engine, second)}")
        assertEquals(
            emptyList<String>(), bootLog.emittedDdl(),
            "the second start of an unchanged database still emitted DDL, so every start does. " +
                diagnosis(engine, second)
        )
    }

    @Test
    fun theBootUpgradeAddsStatusDataToAnExistingDatabase() = onEachEngine { engine ->
        engine.reset(testDb)
        engine.boot(testDb)
        closeDatabase()

        // Roll the schema back to the shape it had before status_data and hub_map_name were added. The
        // columns are dropped from the generated schema rather than hand-written, so what is upgraded
        // here is the schema that actually ships.
        engine.open(testDb).use {
            it.exec("ALTER TABLE `players` DROP COLUMN `status_data`")
            it.exec("ALTER TABLE `plugin_data` DROP COLUMN `hub_map_name`")
        }

        val failure = engine.bootCatching(testDb)
        val why by lazy { diagnosis(engine, failure) }
        assertNull(failure, "the boot upgrade could not restore the two columns. $why")

        val emitted = bootLog.emittedDdl()
        assertTrue(emitted.any { it.contains("status_data", true) }, "players.status_data was not offered. $why")
        assertTrue(emitted.any { it.contains("hub_map_name", true) }, "plugin_data.hub_map_name was not offered. $why")

        engine.open(testDb).use { connection ->
            val statusData = connection.column("players", "status_data")
            assertNotNull(statusData, "players.status_data was not restored. $why")
            assertEquals("YES", statusData.second, "players.status_data came back NOT NULL")
            assertNotNull(connection.column("plugin_data", "hub_map_name"), "hub_map_name was not restored. $why")
        }
    }

    @Test
    fun aVersionFourDatabaseTakesTheGenericScriptAndReachesTheBaseline() = onEachEngine { engine ->
        engine.reset(legacyDb)
        engine.open(legacyDb).use { it.execScript(VERSION_FOUR_SCHEMA) }

        val failure = engine.bootCatching(legacyDb)
        val why by lazy { diagnosis(engine, failure) }

        assertTrue(
            bootLog.any { it.contains(bundle["database.upgrade.execute", "v5.sql"]) },
            "the upgrade did not run v5.sql. $why"
        )
        assertTrue(
            bootLog.none { it.contains("v5_h2.sql") || it.contains("v5_postgres.sql") },
            "the upgrade reached for another engine's script. $why"
        )
        assertNull(failure, "a version 4 database could not boot. $why")

        // An upgrade that reached the baseline but skipped work has to say both halves. v5.sql skips
        // all five of its map_ratings statements here, and on any real server too: that table is never
        // created by the scripts, only by SchemaUtils after this point. Before this change the boot
        // said nothing at all about them.
        assertTrue(
            bootLog.any { it.contains("statement(s) failed and were skipped as non-critical") },
            "the boot did not report the statements it skipped. $why"
        )
        assertEquals(
            5, bootLog.swallowed().size,
            "v5.sql skipped a different number of statements than the five map_ratings ones. $why"
        )
        assertTrue(
            bootLog.none { it.contains("DID NOT FINISH") },
            "an upgrade that reached the baseline was reported as an abort, which teaches an operator " +
                "to ignore the line that matters. $why"
        )

        engine.open(legacyDb).use { connection ->
            assertEquals(
                "5", connection.scalar("SELECT database_version FROM plugin_data ORDER BY id LIMIT 1"),
                "the upgrade did not reach the baseline. $why"
            )
            assertNotNull(connection.column("players", "status_data"), "status_data was not added. $why")
            assertTrue("player_contributions" in connection.tables(), "v5.sql did not create player_contributions")
        }
    }

    /**
     * A database already carrying the v5 shape - what a server that upgraded once looks like on every
     * later start. The legacy upgrade has nothing left to do, so this is the add-missing-columns pass on
     * a schema the legacy scripts built rather than SchemaUtils.
     *
     * It asserts the row is still usable afterwards rather than only that nothing threw: a boot that
     * survives by skipping the repair it needed has to be told apart from one that had nothing to do.
     */
    @Test
    fun aDatabaseAlreadyAtTheBaselineStartsAndItsRowStaysUsable() = onEachEngine { engine ->
        engine.reset(legacyDb)
        engine.open(legacyDb).use {
            it.execScript(VERSION_FOUR_SCHEMA)
            it.execScript(VERSION_FIVE_ADDITIONS)
            it.execScript(BASELINE_STAMP)
        }

        val failure = engine.bootCatching(legacyDb)
        val why by lazy { diagnosis(engine, failure) }
        assertNull(failure, "a database at the baseline could not start, so the plugin would not load. $why")

        // Nothing for the legacy path to do, and the boot has to say so plainly - that line is the
        // whole point of the outcome report, and it is the one an operator reads to know the schema is
        // current rather than half migrated.
        assertTrue(
            bootLog.any { it.contains("no legacy upgrade is outstanding") },
            "a boot with no upgrade outstanding said nothing that says so. $why"
        )

        runBlocking {
            val stored = getPluginData()
            assertNotNull(stored, "plugin_data could not be read back after the boot. $why")
            stored.hubMapName = "hub-" + System.nanoTime()
            assertTrue(stored.update(), "plugin_data could not be written after the boot. $why")
        }
    }

    /**
     * A legacy database at the baseline that is missing one of the tables this build declares.
     *
     * The generated `CREATE` carries an `INT UNSIGNED` foreign key against the legacy `BIGINT`
     * `players.id`, which both engines refuse. The table that cannot be built has to cost its own
     * feature and nothing else: the tables declared after it still have to be created, and the boot
     * still has to finish. On a healthy upgrade `v5.sql` builds this table itself, in matching types,
     * so this is the state a database is left in when that script did not run.
     */
    @Test
    fun aTableTheEngineRefusesDoesNotTakeTheRestOfTheSchemaWithIt() = onEachEngine { engine ->
        engine.reset(legacyDb)
        engine.open(legacyDb).use {
            it.execScript(VERSION_FOUR_SCHEMA)
            it.execScript(BASELINE_STAMP)
        }

        val failure = engine.bootCatching(legacyDb)
        val why by lazy { diagnosis(engine, failure) }
        assertNull(failure, "one table the engine refused ended the whole boot. $why")
        assertTrue(
            bootLog.any { it.contains("could not create player_contributions") },
            "the refusal was never reported, so an operator has nothing to read. $why"
        )

        engine.open(legacyDb).use { connection ->
            val tables = connection.tables()
            assertTrue("player_contributions" !in tables, "the fixture did not reproduce the refusal. $why")
            // Both are declared after ContributionTable, so their presence is what says the loop carried on.
            for (table in listOf("map_ratings", "server_routing")) {
                assertTrue(table in tables, "$table was lost with the table that could not be created. $why")
            }
            // And the repair pass still ran for the tables that are there, rather than being skipped
            // wholesale because one of them could not be introspected.
            assertNotNull(
                connection.column("players", "status_data"),
                "the missing table also cost the other tables their column repairs. $why"
            )
        }
    }

    /**
     * The version stamp only moves for a step that finished.
     *
     * The rename is what is meant to break the upgrade: v5.sql opens by deleting duplicate rows from
     * `players`, and `Database.kt` treats a failing statement naming that table as critical.
     */
    @Test
    fun aFailedUpgradeLeavesTheStoredVersionWhereItWas() = onEachEngine { engine ->
        engine.reset(legacyDb)
        engine.open(legacyDb).use {
            it.execScript(VERSION_FOUR_SCHEMA)
            it.exec("ALTER TABLE `player_achievements` DROP FOREIGN KEY `fk_player_achievements_player_id__id`")
            it.exec("RENAME TABLE `players` TO `players_hidden`")
        }

        // Whether the rest of the boot survives is the subject of the tests above.
        val failure = engine.bootCatching(legacyDb)
        val why by lazy { diagnosis(engine, failure) }

        assertTrue(
            bootLog.any { it.contains("Legacy database upgrade did not finish") },
            "the upgrade did not abort, so this proves nothing about the version stamp. $why"
        )
        engine.open(legacyDb).use { connection ->
            assertEquals(
                "4", connection.scalar("SELECT database_version FROM plugin_data ORDER BY id LIMIT 1"),
                "an upgrade that aborted still advanced the stored version. $why"
            )
        }

        // The version stamp being right is only half of it: the boot carried on into SchemaUtils and
        // Flyway on a half-migrated schema, and the only thing that said so was the warn above, which
        // an operator has to know to look for. The outcome block is what they read instead.
        assertTrue(
            bootLog.any { it.contains("DID NOT FINISH") },
            "the boot never stated that it had come up on a half-migrated schema. $why"
        )
        assertTrue(
            bootLog.any { it.contains("still reads 4") },
            "the outcome report did not name the version the database is actually on. $why"
        )
    }

    /**
     * A schema the `resources/sql` scripts built has none of the unique indexes the Kotlin tables
     * declare, and the boot repair pass drops every statement that would add one - deliberately, since
     * a unique index over a live column that already holds duplicates fails the ALTER and would take
     * the boot with it. What was missing was any record of the refusal.
     *
     * If this finds nothing declined, the premise of the finding is wrong for these engines and the
     * assertion message is the reading.
     */
    @Test
    fun theBootNamesTheRepairsItRefusesToMake() = onEachEngine { engine ->
        engine.reset(legacyDb)
        engine.open(legacyDb).use {
            it.execScript(VERSION_FOUR_SCHEMA)
            it.execScript(VERSION_FIVE_ADDITIONS)
            it.execScript(BASELINE_STAMP)
        }

        val failure = engine.bootCatching(legacyDb)
        val why by lazy { diagnosis(engine, failure) }
        assertNull(failure, "a database at the baseline could not start. $why")

        val declined = bootLog.declinedRepairs()
        assertTrue(
            declined.isNotEmpty(),
            "the boot declined nothing on a schema the legacy scripts built, so either " +
                "addMissingColumnsStatements offers no index statements on this engine or there is " +
                "nothing to add. $why"
        )
        // Named against players itself, not merely mentioning it: a foreign key on another table
        // carries players in its REFERENCES clause, and a report that pointed at the wrong table would
        // send an operator to look for an index that was never missing.
        assertTrue(
            declined.any { it.startsWith(DECLINED_TAG + "players:") },
            "no repair was declined against players, whose uuid and name unique indexes are the ones " +
                "the legacy schema is missing. Declined: ${declined.report()}"
        )
    }

    /**
     * The v3 step picks the engine's own script against a live server, which is the half of the suffix
     * fix `LegacyMigrationTest` can only assert against dialect objects. Neither engine ships a
     * `v4_<engine>.sql`, so both must land on the generic file and neither may reach for `_h2`.
     */
    @Test
    fun aVersionThreeDatabasePicksTheGenericScript() = onEachEngine { engine ->
        engine.reset(legacyDb)
        engine.open(legacyDb).use { it.execScript(VERSION_THREE_SCHEMA) }

        val failure = engine.bootCatching(legacyDb)

        assertTrue(
            bootLog.any { it.contains(bundle["database.upgrade.execute", "v4.sql"]) },
            "a v3 database did not reach v4.sql. ${diagnosis(engine, failure)}"
        )
    }

    /**
     * The account merge locks both player rows for the length of its transaction.
     *
     * Every value it writes is an absolute one computed in Kotlin from a snapshot, so without the lock
     * a counter another of the six servers committed between the read and the write was overwritten by
     * a sum from before it existed. `SELECT ... FOR UPDATE` is the part of that fix an H2-backed suite
     * cannot vouch for, so it is exercised here against the engine the servers actually run.
     */
    @Test
    fun anAccountMergeLocksBothRowsAndStillCompletes() = onEachEngine { engine ->
        engine.reset(testDb)
        engine.boot(testDb)

        val from = "MERGEFROMAAAAAAAAAAAAA=="
        val to = "MERGETOAAAAAAAAAAAAAAA=="
        engine.open(testDb).use {
            it.exec("INSERT INTO `players` (`name`, `uuid`, `exp`) VALUES ('merge-from', '$from', 30)")
            it.exec("INSERT INTO `players` (`name`, `uuid`, `exp`) VALUES ('merge-to', '$to', 12)")
        }

        // Exposed drops the FOR UPDATE clause silently when the dialect reports it unsupported, so
        // the merge would go on passing every assertion below with no lock at all. This is the flag it
        // consults, and it is the only thing standing between this fix and a quiet no-op.
        assertTrue(
            defaultDatabase!!.supportsSelectForUpdate,
            "$engine reports no SELECT ... FOR UPDATE, so the account merge runs unlocked"
        )

        val result = runBlocking { mergePlayerAccounts(from, to) }
        assertTrue(result.startsWith("Merged"), "the merge did not run on $engine: $result")

        engine.open(testDb).use { connection ->
            assertEquals(
                "42", connection.scalar("SELECT exp FROM players WHERE uuid = '$to'"),
                "the merge did not carry the source's exp across on $engine"
            )
            assertNull(
                connection.scalar("SELECT uuid FROM players WHERE uuid = '$from'"),
                "the source row survived the merge on $engine"
            )
        }
    }

    private companion object {
        /** The tag `Database.kt` prints each add-missing-columns statement under. */
        const val DDL_TAG = "[Database] "

        /** The tag `Database.kt` prints each repair it refuses to attempt under. */
        const val DECLINED_TAG = "[Database/schema] repair declined on "

        /**
         * The schema a database upgraded by `sql/v4.sql` has: v3, as `database-v3.mv.db` holds it, with
         * every rename, drop and add of that script applied. This is the shape the production servers
         * are in, and it is not a shape `SchemaUtils` can produce: `plugin_data.id` is a keyless
         * nullable INTEGER and `players.id` a signed BIGINT, because that is what the script wrote.
         */
        const val VERSION_FOUR_SCHEMA = """
CREATE TABLE `players` (
    `name` VARCHAR(256),
    `uuid` VARCHAR(25),
    `language_tag` VARCHAR(10) NOT NULL DEFAULT 'en',
    `block_place_count` INTEGER NOT NULL DEFAULT 0,
    `block_break_count` INTEGER NOT NULL DEFAULT 0,
    `level` INTEGER NOT NULL DEFAULT 0,
    `exp` INTEGER NOT NULL DEFAULT 0,
    `first_played` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_played` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `total_played` INTEGER NOT NULL DEFAULT 0,
    `attack_clear` INTEGER NOT NULL DEFAULT 0,
    `wave_clear` INTEGER NOT NULL DEFAULT 0,
    `pvp_win_count` SMALLINT NOT NULL DEFAULT 0,
    `pvp_lose_count` SMALLINT NOT NULL DEFAULT 0,
    `pvp_eliminated_count` SMALLINT NOT NULL DEFAULT 0,
    `pvp_mvp_count` SMALLINT NOT NULL DEFAULT 0,
    `permission` VARCHAR(50) NOT NULL DEFAULT 'default',
    `account_id` VARCHAR(50) NULL DEFAULT NULL,
    `account_pw` VARCHAR(256) NULL DEFAULT NULL,
    `discord_id` VARCHAR(50) NULL DEFAULT NULL,
    `chat_muted` BOOLEAN NOT NULL DEFAULT FALSE,
    `effect_visibility` BOOLEAN NOT NULL DEFAULT FALSE,
    `effect_level` SMALLINT NULL DEFAULT NULL,
    `effect_color` VARCHAR(20) NULL DEFAULT NULL,
    `hide_ranking` BOOLEAN NOT NULL DEFAULT FALSE,
    `strict_mode` BOOLEAN NOT NULL DEFAULT FALSE,
    `last_login_date` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_logout_date` TIMESTAMP NULL,
    `last_played_world_name` VARCHAR(50) NULL DEFAULT NULL,
    `last_played_world_mode` VARCHAR(50) NULL DEFAULT NULL,
    `is_connected` BOOLEAN NOT NULL DEFAULT FALSE,
    `is_banned` BOOLEAN NOT NULL DEFAULT FALSE,
    `ban_expire_date` TIMESTAMP NULL,
    `attendance_days` INTEGER NOT NULL DEFAULT 0,
    `status` TEXT NULL,
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY
);

INSERT INTO `players` (`name`, `uuid`, `status`) VALUES ('username', 'UPQJIWNSHAQAAAAAAAAAAA==', '{}');

CREATE TABLE `plugin_data` (
    `data` TEXT NOT NULL,
    `id` INTEGER,
    `database_version` INTEGER,
    `hub_map_name` TEXT
);

INSERT INTO `plugin_data` (`data`, `id`, `database_version`) VALUES ('{}', 1, 4);

CREATE TABLE `player_banned` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `names` JSON,
    `ips` JSON,
    `uuid` VARCHAR(25),
    `reason` VARCHAR(256) DEFAULT 'Legacy ban',
    `date` BIGINT DEFAULT 0
);

CREATE TABLE `player_achievements` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `player_id` BIGINT NOT NULL,
    `achievement_name` VARCHAR(100) NOT NULL,
    `completed_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_player_achievements_player_id__id` FOREIGN KEY (`player_id`) REFERENCES `players`(`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
)
"""

        /** The table `sql/v5.sql` adds on top of [VERSION_FOUR_SCHEMA], in the script's own types. */
        const val VERSION_FIVE_ADDITIONS = """
CREATE TABLE `player_contributions` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `player_id` BIGINT NOT NULL,
    `game_mode` VARCHAR(20) NOT NULL,
    `map_name` VARCHAR(64),
    `score` DOUBLE NOT NULL,
    `recorded_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_player_contributions_player_id__id` FOREIGN KEY (`player_id`) REFERENCES `players`(`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
)
"""

        /**
         * The stamp `updatePluginVersion` writes once the v5 step finishes. With it the boot skips the
         * legacy path entirely, which is what every start after the first one does.
         */
        const val BASELINE_STAMP = "UPDATE `plugin_data` SET `database_version` = 5"

        /**
         * Enough of the pre-fork v3 schema to be recognised as one and to get `v4.sql` started: the
         * tables it renames, the one it reads bans out of, and the `db` table `upgradeLegacyDatabase`
         * looks for when `plugin_data` does not exist yet. The columns are not transcribed, because the
         * only assertion made about this database is which script the boot names.
         */
        const val VERSION_THREE_SCHEMA = """
CREATE TABLE `banned` (`type` INTEGER NOT NULL, `data` VARCHAR(255) NOT NULL);

CREATE TABLE `player` (`name` VARCHAR(255) NOT NULL, `uuid` VARCHAR(255) NOT NULL);

INSERT INTO `player` VALUES ('username', 'UPQJIWNSHAQAAAAAAAAAAA==');

CREATE TABLE `data` (`data` TEXT NOT NULL);

INSERT INTO `data` VALUES ('{}');

CREATE TABLE `db` (`version` INTEGER NOT NULL);

INSERT INTO `db` VALUES (3)
"""
    }
}
