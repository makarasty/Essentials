package essential.common.database

import PluginTest.Companion.loadGame
import PluginTest.Companion.stopPlugin
import arc.util.Log
import essential.common.bundle
import essential.common.database.data.checkPlayerBanned
import essential.common.database.data.getPlayerData
import essential.common.database.data.getPluginData
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
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
 * Boots [databaseInit] against a real PostgreSQL server, and walks the `v3 -> v4 -> v5` legacy upgrade
 * over it.
 *
 * This is the only coverage the repository has of that chain on PostgreSQL. It used to start its own
 * `postgres:17.0` container through Testcontainers, which needs Docker; there is no Docker on the
 * machine this suite is developed on, so the test had never run - not once, on any machine anybody
 * could point at. It now uses a server that is already there, and is skipped by a JUnit assumption when
 * none is, because a test that fails on absence gets deleted along with its coverage.
 *
 * Starting one:
 *
 *     initdb -D D:/scratch/pg/data -U postgres --pwprompt      # password: essential
 *     pg_ctl -D D:/scratch/pg/data -o "-p 5433" start
 *
 * The database named below is dropped and recreated by every test here, so point this at a scratch
 * instance only. Connection details move with -Dessential.test.postgres.host / .port / .user /
 * .password; the database name is generated per run and needs no property.
 *
 * ## What this class asserts, and what it deliberately does not
 *
 * Three of these assertions pin behaviour that is **wrong**. The `v4 -> v5` step cannot complete on
 * PostgreSQL: `v5_postgres.sql` opens with five statements against `map_ratings`, a table that
 * `SchemaUtils` creates *after* the legacy upgrade has run, so the first of them answers `42P01` - and
 * on PostgreSQL a failed statement aborts the transaction the whole script shares, so everything after
 * it answers `25P02` and the step is abandoned with `plugin_data.database_version` still at 4. Each
 * such assertion says so on the line, and names what it will look like when somebody fixes it. Chip 3
 * of the `green-2026-09-09` run was asked to report that defect rather than repair it, so the tests
 * describe the engine's real answers today; the moment the repair lands they fail, loudly, which is the
 * signal that they should be turned round.
 */
class LivePostgresUpgradeTest {
    private val host = System.getProperty("essential.test.postgres.host", "127.0.0.1")
    private val port = System.getProperty("essential.test.postgres.port", "5433")
    private val user = System.getProperty("essential.test.postgres.user", "postgres")
    private val pass = System.getProperty("essential.test.postgres.password", "essential")

    /** Written from r2dbc's own threads, so not an ordinary list. */
    private var bootLog = CopyOnWriteArrayList<String>()
    private var booted = false

    private fun jdbc(database: String) =
        "jdbc:postgresql://$host:$port/$database?connectTimeout=3&socketTimeout=30"

    private fun open(database: String = "postgres"): Connection =
        DriverManager.getConnection(jdbc(database), user, pass)

    private fun answers() =
        runCatching { open().use { it.createStatement().use { s -> s.execute("SELECT 1") } } }.isSuccess

    /** Skips the test when nothing is listening, which is this machine's and CI's normal state. */
    private fun onAServer(body: () -> Unit) {
        assumeTrue("no PostgreSQL server on $host:$port, skipping", answers())
        try {
            body()
        } finally {
            closeDatabase()
        }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.execScript(script: String) =
        script.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }

    private fun Connection.tables(): Set<String> =
        createStatement().use { statement ->
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            ).use { rows -> buildSet { while (rows.next()) add(rows.getString(1).lowercase()) } }
        }

    private fun Connection.column(table: String, column: String): Triple<String, String, String?>? =
        prepareStatement(
            "SELECT data_type, is_nullable, column_default FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?"
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, column)
            statement.executeQuery().use { rows ->
                if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getString(3)) else null
            }
        }

    private fun Connection.scalar(sql: String): String? =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    /** FORCE because the r2dbc pool's connections outlive the dispose by a moment. */
    private fun reset() = open().use {
        it.exec("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        it.exec("CREATE DATABASE $DATABASE")
    }

    private fun seedVersionThree() = open(DATABASE).use {
        it.execScript(readResource("v3_postgres.sql"))
    }

    private fun readResource(name: String) =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "$name is not on the test classpath" }
            .bufferedReader().use { it.readText() }

    /**
     * One plugin boot against the scratch database. Everything the plugin logs lands in [bootLog], which
     * is read after the call rather than returned, because a boot that throws is the case that log is
     * most needed for: `Database.kt` prints the script the upgrade chose, every statement it swallowed
     * and every repair statement it ran.
     */
    private fun boot() {
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
            runBlocking { databaseInit("postgresql://$host:$port/$DATABASE", user, pass) }
        } finally {
            Log.logger = previous
        }
    }

    private fun bootCatching(): Throwable? = runCatching { boot() }.exceptionOrNull()

    private fun List<String>.swallowed() = filter { it.startsWith("Failed to execute statement:") }

    private fun List<String>.emittedDdl() = filter { it.startsWith(DDL_TAG) }.map { it.removePrefix(DDL_TAG) }

    private fun List<String>.report() = joinToString("\n").ifEmpty { "(nothing was logged)" }

    /** Everything the next person needs to place a failure: what ran, what was swallowed, what threw. */
    private fun diagnosis(failure: Throwable?) = buildString {
        append("PostgreSQL on ").append(host).append(':').append(port).append(", database ").append(DATABASE)
        append("\nBoot log:\n").append(bootLog.report())
        append("\nStatements the legacy upgrade swallowed:\n").append(bootLog.swallowed().report())
        append("\nDDL the boot ran:\n").append(bootLog.emittedDdl().report())
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
        stopBoot()
        runCatching { open().use { it.exec("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)") } }
    }

    /** Ends a boot without dropping what it was booted against, for a test that starts twice. */
    private fun stopBoot() {
        booted = false
        // stopPlugin sends SHUTDOWN to every open database. Drop the reference to the server's one
        // first, so only the H2 world-history database gets it.
        defaultDatabase = null
        stopPlugin()
    }

    /**
     * The whole point of this class: a pre-fork version 3 database, upgraded by the plugin's own boot.
     *
     * The fixture is the same `v3_postgres.sql` the container test used, so the row asserted at the end
     * is the row that test asserted, migrated by the same two scripts.
     */
    @Test
    fun aVersionThreeDatabaseWalksTheUpgradeChain() = onAServer {
        reset()
        seedVersionThree()

        val failure = bootCatching()
        val why by lazy { diagnosis(failure) }
        assertNull(failure, "a version 3 PostgreSQL database could not boot at all. $why")

        val dialect = defaultDatabase?.config?.explicitDialect
        assertNotNull(dialect, "the database was never connected. $why")
        assertTrue(dialect is PostgreSQLDialect, "databaseInit chose ${dialect::class} for a postgresql:// url")

        // The suffixed scripts exist so this engine never sees MySQL syntax. Both steps have to name
        // their own file and neither may fall back to the generic one or reach for H2's.
        assertTrue(
            bootLog.any { it.contains(bundle["database.upgrade.execute", "v4_postgres.sql"]) },
            "the v3 step did not run v4_postgres.sql. $why"
        )
        assertTrue(
            bootLog.any { it.contains(bundle["database.upgrade.execute", "v5_postgres.sql"]) },
            "the v4 step did not run v5_postgres.sql. $why"
        )
        assertTrue(
            bootLog.none { it.contains("v4.sql") || it.contains("v5.sql") || it.contains("_h2.sql") },
            "the upgrade reached for another engine's script. $why"
        )

        open(DATABASE).use { connection ->
            // The v3 step is clean: v4_postgres.sql runs all 149 of its statements without one failing.
            assertTrue(
                "player_banned" in connection.tables(),
                "v4_postgres.sql did not finish - player_banned is one of the tables it creates. $why"
            )
            assertNull(
                connection.column("players", "freeze"),
                "v4_postgres.sql did not drop the columns it renames past. $why"
            )

            // DEFECT, reported not repaired: the v4 -> v5 step aborts, so the stamp never reaches 5.
            // v5_postgres.sql's first map_ratings statement answers 42P01 - SchemaUtils creates that
            // table after the upgrade, not before - and PostgreSQL then refuses every later statement in
            // the transaction the script shares with 25P02, including the UPDATE that writes this row.
            // When that is fixed this assertion reads 5, and this test says so rather than passing
            // quietly on either answer.
            assertEquals(
                "4", connection.scalar("SELECT database_version FROM plugin_data ORDER BY id LIMIT 1"),
                "the stored version is no longer 4, so the v4 -> v5 step now completes on PostgreSQL " +
                    "and this assertion is the one that needs changing, to 5. $why"
            )
            assertTrue(
                bootLog.any { it.contains("Legacy database upgrade did not finish") },
                "the upgrade no longer aborts, so the stored version above should have advanced. $why"
            )
        }

        // The row the container test asserted, carried through v4_postgres.sql's column renames and its
        // bigint-to-timestamp conversions. This is the half of the old test that was about migration
        // rather than about Docker, and it passes.
        runBlocking {
            val player = getPlayerData("migration-test-player")
            assertNotNull(player, "the migrated player is gone after the upgrade. $why")
            assertEquals(122213, player.blockPlaceCount, "block_place_count did not survive the upgrade. $why")
            assertEquals(1, player.level, "level did not survive the upgrade. $why")
            assertEquals(56, player.exp, "exp did not survive the upgrade. $why")
            assertTrue(!checkPlayerBanned(player.player), "the migrated player came back banned. $why")

            // The two rows of the v3 `banned` table, which v4_postgres.sql rewrites into player_banned
            // as a jsonb array each - one keyed by name and uuid, one by ip. checkPlayerBanned returns
            // false when its own query throws, so these two are the assertions that say the migrated
            // rows are readable as well as present.
            assertTrue(
                checkPlayerBanned("test-banned-player", "198.51.100.1", "no-such-name"),
                "the v3 name ban did not survive v4_postgres.sql. $why"
            )
            assertTrue(
                checkPlayerBanned("no-such-uuid", "203.0.113.7", "no-such-name"),
                "the v3 ip ban did not survive v4_postgres.sql. $why"
            )
        }
    }

    /**
     * What the boot then builds on top of a half-upgraded legacy schema, which is the state every
     * PostgreSQL server upgrading from v3 or v4 is actually left in today.
     *
     * `SchemaUtils.create` has to survive meeting the legacy `players`, whose `id` is a `bigserial`
     * against this build's `uinteger` declaration; on MySQL and MariaDB that pairing is refused outright.
     * And `plugin_data` has to stay readable and writable afterwards - a boot that survives by skipping
     * every repair it needed has to be told apart from one that had nothing to do.
     */
    @Test
    fun theLegacySchemaSurvivesTheCreateAndRepairPasses() = onAServer {
        reset()
        seedVersionThree()

        val failure = bootCatching()
        val why by lazy { diagnosis(failure) }
        assertNull(failure, "the boot did not survive its own schema passes on a legacy database. $why")

        open(DATABASE).use { connection ->
            val tables = connection.tables()
            for (table in listOf(
                "players", "plugin_data", "player_banned", "player_achievements",
                "map_ratings", "server_routing"
            )) {
                assertTrue(table in tables, "SchemaUtils.create left out $table. $why")
            }
            // DEFECT, same one: on a finished upgrade v5_postgres.sql creates this table itself, in
            // bigint, before SchemaUtils ever looks at it. The step aborts, so what is here instead is
            // whatever SchemaUtils made of it beside a bigserial players.id - and PostgreSQL, unlike
            // MySQL, accepts a foreign key whose two sides differ in width, so it is here.
            assertTrue(
                "player_contributions" in tables,
                "SchemaUtils could not create player_contributions beside the legacy players.id. $why"
            )

            assertNotNull(connection.column("players", "status_data"), "status_data was not added. $why")
            assertNotNull(connection.column("plugin_data", "hub_map_name"), "hub_map_name is missing. $why")
        }

        // A repair statement the engine refuses is logged rather than thrown, and on PostgreSQL a
        // refusal that was not given its own transaction would take the rest of the pass with it.
        assertTrue(
            bootLog.none { it.contains("current transaction is aborted") && it.startsWith(DDL_TAG) },
            "a schema repair poisoned the transaction the next one needed. $why"
        )

        runBlocking {
            val stored = getPluginData()
            assertNotNull(stored, "plugin_data could not be read back after the boot. $why")
            stored.hubMapName = "hub-" + System.nanoTime()
            assertTrue(stored.update(), "plugin_data could not be written after the boot. $why")
        }
    }

    /**
     * The second start of the same database.
     *
     * On a healthy upgrade there is nothing left to do and the boot emits no DDL at all. Today the
     * version stamp is still 4, so the second start runs the whole failing v5 step again - which is the
     * cost of the defect above, and the reason it is a blocker rather than a one-off: it is not a
     * database that upgraded badly once, it is one that fails the same upgrade on every start forever.
     */
    @Test
    fun theSecondStartOfALegacyDatabaseRepeatsTheFailedUpgrade() = onAServer {
        reset()
        seedVersionThree()
        boot()
        stopBoot()

        val failure = bootCatching()
        val why by lazy { diagnosis(failure) }
        assertNull(failure, "the second start of an upgraded database failed. $why")

        // DEFECT: when the v4 -> v5 step is fixed the stamp reaches 5 on the first start and this second
        // one skips the legacy path entirely, so both of these assertions invert.
        assertTrue(
            bootLog.any { it.contains(bundle["database.upgrade.execute", "v5_postgres.sql"]) },
            "the second start no longer re-runs v5_postgres.sql, so the first start must now be " +
                "completing it - invert this test. $why"
        )
        open(DATABASE).use { connection ->
            assertEquals(
                "4", connection.scalar("SELECT database_version FROM plugin_data ORDER BY id LIMIT 1"),
                "the stored version moved on a start that changed nothing. $why"
            )
        }
    }

    private companion object {
        /** The tag `Database.kt` prints each add-missing-columns statement under. */
        const val DDL_TAG = "[Database] "

        /**
         * One database per run rather than the shared `essential_test`, which other tests use. Held on
         * the companion so every test in one JVM names the same one, and dropped by each teardown.
         */
        val DATABASE = "essential_upgrade_" + System.currentTimeMillis()
    }
}
