package essential.common.database

import PluginTest.Companion.loadGame
import PluginTest.Companion.loadPlugin
import PluginTest.Companion.stopPlugin
import essential.common.database.data.getAllWorldHistory
import kotlinx.coroutines.runBlocking
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
        runCatching { stopPlugin() }
    }

    @Test
    fun aBootOverAnOlderHistoryTableAddsTheColumnsAndKeepsTheRowsAlreadyInIt() {
        runBlocking {
            suspendTransaction(db = worldHistoryDatabase) {
                exec("ALTER TABLE world_history DROP COLUMN kind")
                exec("ALTER TABLE world_history DROP COLUMN uuid")
                // Named columns only, as a jar without these columns would write. `id` and `created_at`
                // are left to their default and their auto-increment, exactly as that jar left them.
                exec(
                    "INSERT INTO world_history (time, player, action, x, y, tile, rotate, team, value) " +
                        "VALUES (1000, 'oldjar', 'config', 40, 45, 'sorter', 0, 'sharded', 'copper')"
                )
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

        val old = migrated.singleOrNull { it.player == "oldjar" }
        assertEquals(
            1, migrated.count { it.player == "oldjar" },
            "the row written before the migration did not survive it: $migrated"
        )
        checkNotNull(old)
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
