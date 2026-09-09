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
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
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
 * ## The defect these were written for
 *
 * The `v4 -> v5` step could not complete on PostgreSQL at all. `v5_postgres.sql`'s fourth statement
 * touches `map_ratings`, a table `SchemaUtils` creates *after* the legacy upgrade has run, so it
 * answered `42P01` - and on PostgreSQL a failed statement aborts the transaction the whole script
 * shares, so the four statements after it answered `25P02` and were swallowed too, and then the
 * script's last statement, `CREATE TABLE player_contributions`, was rethrown as critical because it
 * names `players` in its foreign key. The step was abandoned with `plugin_data.database_version` still
 * at 4, and every later start repeated it - failing one statement earlier the second time round, on
 * `42703 column is_upvote does not exist`, since by then `SchemaUtils` had built `map_ratings` at the
 * current shape.
 *
 * Both halves are repaired: the `map_ratings` statements in `v5_postgres.sql` can no longer fail in any
 * of those states, and `applyLegacyScript` now rolls a swallowed failure back to its own savepoint
 * instead of leaving the transaction poisoned. The stored version reaching 5, the second start finding
 * nothing to do, and `aSwallowedFailureDoesNotCostTheStatementsAfterIt` are the assertions that say so.
 * All three are PostgreSQL-only, and so is the defect: the other engines implicitly commit at every DDL
 * statement, so they never had a transaction to poison and never had a whole-script rollback either.
 */
class LivePostgresUpgradeTest {
    private val host = System.getProperty("essential.test.postgres.host", "127.0.0.1")
    private val port = System.getProperty("essential.test.postgres.port", "5433")
    private val user = System.getProperty("essential.test.postgres.user", "postgres")
    private val pass = System.getProperty("essential.test.postgres.password", "essential")

    /** Written from r2dbc's own threads, so not an ordinary list. */
    private var bootLog = CopyOnWriteArrayList<String>()
    private var booted = false

    /** Separate from [booted]: a test that fails while seeding still has a database to drop. */
    private var created = false

    private fun jdbc(database: String) =
        "jdbc:postgresql://$host:$port/$database?connectTimeout=3&socketTimeout=30"

    private fun open(database: String = "postgres"): Connection =
        DriverManager.getConnection(jdbc(database), user, pass)

    /**
     * Skips the test when nothing is listening, which is this machine's and CI's normal state.
     *
     * The reason travels with the skip: a wrong password, a listener that is not PostgreSQL and an
     * absent server all fail the same probe, and a permanent silent skip is the same thing as the
     * permanent failure this class replaced.
     */
    private fun onAServer(body: () -> Unit) {
        val probe = runCatching { open().use { it.createStatement().use { s -> s.execute("SELECT 1") } } }
        assumeTrue(
            "no PostgreSQL server answering on $host:$port as $user (${probe.exceptionOrNull()?.message}), skipping",
            probe.isSuccess
        )
        // The teardown is a JUnit @AfterTest and runs on its own, including after an assumption.
        body()
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
        created = true
    }

    private fun seedVersionThree() = open(DATABASE).use {
        it.execScript(readResource("v3_postgres.sql"))
    }

    /**
     * `map_ratings` as an older build of this plugin declared it, before difficulty and rating replaced
     * a single up-or-down vote.
     *
     * No legacy script ever created this table - `SchemaUtils` did - so a v3 fixture does not carry it,
     * and without it `v5_postgres.sql`'s conversion is skipped by its own `IF EXISTS` guards and never
     * runs. `rated_at` is here because that build declared it too, with the `CURRENT_TIMESTAMP` default
     * `defaultExpression` renders, and the upgrade leaves it behind.
     */
    private fun seedLegacyMapRatings() = open(DATABASE).use {
        it.exec(
            "CREATE TABLE map_ratings (id BIGSERIAL PRIMARY KEY, map_name VARCHAR(100), " +
                "map_hash VARCHAR(100), player_uuid VARCHAR(25), is_upvote BOOLEAN NOT NULL, " +
                "rated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"
        )
        it.exec("ALTER TABLE map_ratings ADD CONSTRAINT map_ratings_map_hash_unique UNIQUE (map_hash)")
        it.exec("INSERT INTO map_ratings (map_name, map_hash, player_uuid, is_upvote) VALUES ('up', 'h1', 'u1', TRUE)")
        it.exec("INSERT INTO map_ratings (map_name, map_hash, player_uuid, is_upvote) VALUES ('down', 'h2', 'u2', FALSE)")
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

    /** `Database.kt` logs its refusals under the same tag as the DDL it runs, so those are filtered out. */
    private fun List<String>.emittedDdl() =
        filter { it.startsWith(DDL_TAG) && !it.contains("could not ") && !it.contains("refused") }
            .map { it.removePrefix(DDL_TAG) }

    private fun List<String>.refusals() = filter { it.contains("could not ") || it.contains("refused") }

    private fun List<String>.report() = joinToString("\n").ifEmpty { "(nothing was logged)" }

    /** Everything the next person needs to place a failure: what ran, what was swallowed, what threw. */
    private fun diagnosis(failure: Throwable?) = buildString {
        append("PostgreSQL on ").append(host).append(':').append(port).append(", database ").append(DATABASE)
        append("\nStatements the legacy upgrade swallowed:\n").append(bootLog.swallowed().report())
        append("\nDDL the boot ran:\n").append(bootLog.emittedDdl().report())
        append("\nWhat the boot refused:\n").append(bootLog.refusals().report())
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
        if (booted) stopBoot()
        if (!created) return
        created = false
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
        seedLegacyMapRatings()

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
            // Asserted on what only that script can do - it drops `banned` after folding it into
            // player_banned - because SchemaUtils creates player_banned afterwards either way.
            assertTrue(
                "banned" !in connection.tables(),
                "v4_postgres.sql did not fold the v3 banned table away. $why"
            )
            assertNull(
                connection.column("players", "freeze"),
                "v4_postgres.sql did not drop the columns it renames past. $why"
            )

            // The stamp only moves for a step that finished, so this is the assertion that says the
            // whole chain ran. It read 4 before the map_ratings statements were guarded.
            assertEquals(
                "5", connection.scalar("SELECT database_version FROM plugin_data ORDER BY id LIMIT 1"),
                "the v4 -> v5 step did not reach the baseline. $why"
            )
            assertTrue(
                bootLog.none { it.contains("Legacy database upgrade did not finish") },
                "the upgrade aborted. $why"
            )
            // player_contributions is present either way - SchemaUtils would build it after an aborted
            // upgrade - so the column type is what tells the two apart. The script writes BIGINT, to
            // match the legacy bigserial players.id; SchemaUtils writes Exposed's uinteger as integer.
            assertEquals(
                "bigint", connection.column("player_contributions", "player_id")?.first,
                "player_contributions came from SchemaUtils, so v5_postgres.sql never reached its last " +
                    "statement. $why"
            )

            // The conversion the map_ratings block exists for, on the fixture seeded above. Without a
            // legacy map_ratings every statement in that block is skipped by its own IF EXISTS and the
            // expression below is never executed at all.
            assertEquals(
                "5", connection.scalar("SELECT rating FROM map_ratings WHERE map_name = 'up'"),
                "an upvote did not become a rating of 5. $why"
            )
            assertEquals(
                "1", connection.scalar("SELECT rating FROM map_ratings WHERE map_name = 'down'"),
                "a downvote did not become a rating of 1. $why"
            )
            assertEquals(
                "3", connection.scalar("SELECT difficulty FROM map_ratings WHERE map_name = 'up'"),
                "difficulty was not backfilled. $why"
            )
            assertNull(
                connection.column("map_ratings", "is_upvote"),
                "the vote column was converted and then left in place. $why"
            )
        }

        // Nothing in v5_postgres.sql may fail any more, on any of the shapes above. This is the
        // assertion that catches the next statement somebody adds that can.
        assertEquals(
            emptyList<String>(), bootLog.swallowed(),
            "the upgrade swallowed a statement, which on PostgreSQL poisons the rest of the script. $why"
        )

        // The row the container test asserted, carried through v4_postgres.sql's column renames and its
        // bigint-to-timestamp conversions. This is the half of the old test that was about migration
        // rather than about Docker, and it passes.
        // Guarded, because a repair statement the boot logged rather than threw leaves these reads
        // throwing out of runBlocking with none of the diagnosis above attached.
        val read = runCatching {
            runBlocking {
                val player = getPlayerData("migration-test-player")
                assertNotNull(player, "the migrated player is gone after the upgrade. $why")
                assertEquals(122213, player.blockPlaceCount, "block_place_count did not survive. $why")
                assertEquals(1, player.level, "level did not survive the upgrade. $why")
                assertEquals(56, player.exp, "exp did not survive the upgrade. $why")
                // player.player is a blank pooled entity, not this row, so the row's own identity is what
                // has to be asked about - with an address the fixture does not carry, so a hit is a hit
                // on the uuid and not on the ip.
                assertTrue(
                    !checkPlayerBanned(player.uuid, "198.51.100.9", player.name),
                    "the migrated player came back banned. $why"
                )

                // The two rows of the v3 `banned` table, which v4_postgres.sql rewrites into
                // player_banned as a jsonb array each - one keyed by name and uuid, one by ip.
                // checkPlayerBanned returns false when its own query throws, so these two are the
                // assertions that say the migrated rows are readable as well as present.
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
        val readFailure = read.exceptionOrNull()
        if (readFailure is AssertionError) throw readFailure
        assertNull(readFailure, "the upgraded rows could not be read back at all. $why")
    }

    /**
     * What the boot builds on top of a schema the legacy scripts wrote, rather than one SchemaUtils did.
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
            // player_contributions is deliberately not asserted here: on a finished upgrade the script
            // builds it, so its presence would say nothing about SchemaUtils. Its type is checked in
            // aVersionThreeDatabaseWalksTheUpgradeChain, which is where it discriminates.

            // No legacy script adds this column, so it is the repair pass or nothing. hub_map_name is
            // not asserted beside it: v4_postgres.sql creates plugin_data carrying it already, so that
            // assertion would pass whatever the repair pass did.
            assertNotNull(connection.column("players", "status_data"), "status_data was not added. $why")
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
     * The second start of a database the first start upgraded.
     *
     * This is the assertion the defect cost most: while the step could not finish, the stamp stayed at
     * 4 and every start re-ran the whole failing v5 script, forever. A finished upgrade has to leave the
     * legacy path behind for good.
     */
    @Test
    fun theSecondStartOfAnUpgradedDatabaseLeavesTheLegacyPathAlone() = onAServer {
        reset()
        seedVersionThree()
        assertNull(bootCatching(), "the first start of a version 3 database failed. ${diagnosis(null)}")
        stopBoot()

        val failure = bootCatching()
        val why by lazy { diagnosis(failure) }
        assertNull(failure, "the second start of an upgraded database failed. $why")

        assertTrue(
            bootLog.none { it.contains(bundle["database.upgrade.execute", "v5_postgres.sql"]) },
            "the second start ran the upgrade script again, so the first one did not finish it. $why"
        )
        open(DATABASE).use { connection ->
            assertEquals(
                "5", connection.scalar("SELECT database_version FROM plugin_data ORDER BY id LIMIT 1"),
                "the stored version did not survive a second start. $why"
            )
        }
    }

    /**
     * The swallow rule, made to mean what it says.
     *
     * `upgradeLegacyDatabase` carries on past a failing statement that names neither `players` nor
     * `plugin_data`. On PostgreSQL the transaction the rest of the script shares is aborted from that
     * moment, so every later statement answered `25P02` and one missing table ended the whole step. The
     * savepoint is what makes the swallow local to its own statement.
     *
     * **This is the only one of the two savepoint tests that discriminates.** Reverted, the second
     * statement answers `25P02`, is swallowed in its turn, and `savepoint_probe` is not there.
     */
    @Test
    fun aSwallowedFailureDoesNotCostTheStatementsAfterIt() = onAServer {
        reset()
        boot()

        val script = "ALTER TABLE no_such_table DROP COLUMN nope;CREATE TABLE savepoint_probe (id INT)"
        runBlocking { suspendTransaction { applyLegacyScript(script) } }

        open(DATABASE).use { connection ->
            // diagnosis() is not used here: it reads the log boot() collects, and boot() has already put
            // the handler back by the time this script runs, so it would promise evidence it cannot hold.
            assertTrue(
                "savepoint_probe" in connection.tables(),
                "a swallowed failure took the statement after it down with it. Script: $script. " +
                    "Tables afterwards: ${connection.tables()}"
            )
        }
    }

    /**
     * The other half, which is why the repair is a savepoint per statement rather than a transaction per
     * statement: a critical failure still has to take the whole script back with it. A script that
     * half-applies is worse than one that does not run.
     *
     * This one passes with the savepoint reverted, and is meant to - it is the guard on the shape that
     * was rejected, not evidence for the shape that was taken. Deleting it because it does not
     * discriminate would remove the only check that the rejected shape stays rejected.
     */
    @Test
    fun aCriticalFailureStillRollsTheWholeScriptBack() = onAServer {
        reset()
        boot()

        val script = "CREATE TABLE rollback_probe (id INT);ALTER TABLE players DROP COLUMN no_such_column"
        val failure = runCatching {
            runBlocking { suspendTransaction { applyLegacyScript(script) } }
        }.exceptionOrNull()

        // Asserted on the message, not merely on something having been thrown: a savepoint statement
        // that threw would also leave rollback_probe absent, and both assertions would pass with the
        // machinery under test entirely broken.
        assertTrue(
            generateSequence(failure) { it.cause }.any {
                it.message?.startsWith("Critical statement failed: ALTER TABLE players") == true
            },
            "the statement naming players was not the one that ended the script: $failure"
        )

        open(DATABASE).use { connection ->
            assertTrue(
                "rollback_probe" !in connection.tables(),
                "a critical failure left the statement before it committed. Script: $script. " +
                    "Tables afterwards: ${connection.tables()}"
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
        val DATABASE = "essential_upgrade_${ProcessHandle.current().pid()}_${System.currentTimeMillis()}"
    }
}
