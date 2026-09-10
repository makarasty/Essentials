package essential.common.database

import PluginTest.Companion.loadGame
import PluginTest.Companion.loadPlugin
import PluginTest.Companion.stopPlugin
import essential.common.database.data.getAllWorldHistory
import essential.common.database.table.WorldHistoryTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * The world-history database is a local per-server H2 file and had no migration path at all: it is
 * created by one `SchemaUtils.create(WorldHistoryTable)`, which **skips a table that already exists**,
 * and the boot repair beside it runs over the shared database's tables and never included this one. So a
 * column added to the Kotlin table appeared only on a server that had never booted, and on every other
 * one every insert failed against the old shape. Two findings wanting a column here were refused for
 * that reason before `migrateWorldHistoryColumns` existed.
 *
 * **This test starts from the old shape, because a test that creates the table fresh and finds the
 * column there proves nothing** - that is the same `SchemaUtils.create` path a server that has already
 * booted never reaches.
 *
 * The old shape is produced by dropping the two new columns from a table the current code created,
 * rather than by typing out the previous DDL. That is deliberate and it is the more faithful of the two:
 * every remaining column then comes from the same generator that wrote the file on the six live servers,
 * with none of the guessing about how Exposed renders `uinteger`, `timestamp` or a default expression on
 * H2 that a hand-written `CREATE TABLE` would smuggle in. The two columns are the entire difference
 * between the shapes.
 *
 * **Two properties this class depends on, recorded because a later test added here could break either.**
 *
 * It is the suite's first `loadPlugin(force = true)` over a *running* plugin - every other forced load
 * calls `stopPlugin()` and deletes the H2 files first - so it is also the only place `databaseInit` runs
 * a second time over an existing, populated database. That path was walked for error-level logging and
 * has none. The cost is that generation one's connection pools are replaced without being disposed and
 * leak for the life of the JVM, which `Database.kt` already does on every forced reload and which the
 * `SHUTDOWN` in `stopPlugin` releases.
 *
 * And between the `DROP COLUMN`s and the migration there is a window in which a queued row's insert
 * would name `kind`/`uuid` against a table that is two columns short, fail, and skip `requeueOldest` -
 * destroying that batch and killing the flush loop for the rest of the JVM. It cannot fire as this class
 * stands: `discard()` empties the queue under the flush lock, `flushLoop` skips an empty queue, and the
 * only producer is `CoreEvent.addLog` off tile events, which needs a game tick this class never causes.
 * **A test added here that ticks the game opens that window**, and the fix then is the allowance
 * `WorldHistoryBufferBoundsTest` uses, not a wider drop.
 */
class WorldHistoryMigrationTest {

    @BeforeTest
    fun setup() {
        loadGame(true)
        runBlocking { WorldHistoryBuffer.discard() }
    }

    @AfterTest
    fun teardown() {
        // Hands the next class a database this class did not shape, and puts the listener and timer
        // registrations the second `loadPlugin` added back to the baseline.
        //
        // Bare, not wrapped in runCatching: if stopPlugin throws before its last line the error guard is
        // never reinstalled, pluginLoaded stays true and the H2 files survive into the next class, and
        // swallowing the throw here would hide the only evidence of it.
        stopPlugin()
    }

    @Test
    fun aBootOverAnOlderHistoryTableAddsTheColumnsAndKeepsTheRowsAlreadyInIt() {
        runBlocking {
            suspendTransaction(db = worldHistoryDatabase) {
                exec("ALTER TABLE world_history DROP COLUMN kind")
                exec("ALTER TABLE world_history DROP COLUMN uuid")
                // Written through Exposed rather than as raw SQL, and that is not a style choice. Three
                // of these column names - time, action, value - are SQL keywords, so Exposed quoted them
                // when it wrote the CREATE, and hand-typed SQL has to reproduce that quoting and its
                // case exactly or the statement names columns that do not exist. My two reviewers read
                // the identifier-folding chain in opposite directions and disagreed about which case
                // lands, which is reason enough not to depend on the answer: going through the same
                // builder that created the table renders both sides identically and the question cannot
                // arise. `insert` emits only the columns set here, which is exactly what a jar without
                // kind and uuid emits, so this is that jar's insert and not an imitation of it. `id` and
                // `created_at` are left to the auto-increment and the database default, as it left them.
                WorldHistoryTable.insert { row ->
                    row[WorldHistoryTable.time] = 1000
                    row[WorldHistoryTable.player] = "oldjar"
                    row[WorldHistoryTable.action] = "config"
                    row[WorldHistoryTable.x] = 40
                    row[WorldHistoryTable.y] = 45
                    row[WorldHistoryTable.tile] = "sorter"
                    row[WorldHistoryTable.rotate] = 0
                    row[WorldHistoryTable.team] = "sharded"
                    row[WorldHistoryTable.value] = "copper"
                }
            }
        }

        // The precondition, asserted rather than assumed, and it is what stops this test going vacuous:
        // the read the migration has to make possible is proven impossible first. Exposed projects the
        // table object's own column list, so this select names `kind` and `uuid` and the engine refuses
        // it. If the drop above ever silently stopped working, this line fails and says so here instead
        // of letting the rest of the test pass against a table that was never old-shaped.
        assertFails("world_history still answers a select naming kind and uuid, so the old shape was never set up") {
            runBlocking { getAllWorldHistory() }
        }

        // The production boot path, run over the table as it now stands: databaseInit's
        // SchemaUtils.create skips it for existing, and migrateWorldHistoryColumns repairs it.
        loadPlugin(force = true)

        val migrated = runBlocking { getAllWorldHistory() }

        assertEquals(
            1, migrated.count { it.player == "oldjar" },
            "the row written before the migration did not survive it: $migrated"
        )
        val old = migrated.first { it.player == "oldjar" }
        assertEquals("config", old.action, "the migration rewrote an existing row's action")
        assertEquals("copper", old.value, "the migration rewrote an existing row's value")
        assertEquals(1000L, old.time, "the migration rewrote an existing row's time")
        assertNull(old.kind, "a row written before the column exists cannot have a kind")
        assertNull(old.uuid, "a row written before the column exists cannot have a uuid")

        // And the columns are writable, not merely present - the half that decides whether the two
        // findings waiting on this can be built at all.
        WorldHistoryBuffer.enqueue(
            time = 2000,
            player = "newjar",
            action = "config",
            x = 40,
            y = 45,
            tile = "sorter",
            rotate = 0,
            team = "sharded",
            value = "copper",
            kind = "mindustry.type.Item",
            uuid = "UPQJIWNSHAQAAAAAAAAAAA=="
        )
        runBlocking { WorldHistoryBuffer.flush() }

        val fresh = runBlocking { getAllWorldHistory() }.single { it.player == "newjar" }
        assertEquals("mindustry.type.Item", fresh.kind, "kind did not round-trip through the migrated table")
        assertEquals("UPQJIWNSHAQAAAAAAAAAAA==", fresh.uuid, "uuid did not round-trip through the migrated table")

        // The old row is still readable beside the new one, which is the mixed table every server has
        // for as long as its history survives - rollback has to work over both.
        assertEquals(
            2, runBlocking { getAllWorldHistory() }.size,
            "the migrated table does not hold both the pre-migration row and the post-migration one"
        )
    }
}
