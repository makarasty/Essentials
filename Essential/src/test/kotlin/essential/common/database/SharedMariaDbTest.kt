package essential.common.database

import PluginTest.Companion.createPlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.stopPlugin
import arc.util.Log
import essential.common.database.data.DisplayData
import essential.common.database.data.PlayerData
import essential.common.database.data.PluginData
import essential.common.database.data.createPlayerData
import essential.common.database.data.createPluginData
import essential.common.database.data.getPlayerData
import essential.common.database.data.getPluginData
import essential.common.database.data.plugin.WarpCount
import essential.common.database.table.PluginTable
import essential.core.loadJoinedPlayerData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mindustry.gen.Groups
import org.jetbrains.exposed.v1.core.vendors.MariaDBDialect
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.Assume.assumeTrue
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two plugin instances against one shared MariaDB.
 *
 * Every other test in this suite runs one instance against its own H2 file. The operator runs six
 * servers against one MariaDB, and that is the situation the changed-column write and the plugin_data
 * merge were written for - so it is the situation nothing had ever exercised.
 *
 * An "instance" here is a second in-memory copy of a shared row, which is what a second server is at
 * the database layer: dbSnapshot is the whole of a server's memory of that row, and two servers
 * differ only in when each of them read it. What this cannot model is a second JVM's globals - a
 * second pluginData, a second Mindustry ban list, a second permission file - so the scenarios that
 * turn on those say so where they appear.
 *
 * Skipped by a JUnit assumption when no server answers: this machine does not always have one, and a
 * failure on absence gets the class deleted and the coverage with it. Starting one:
 *
 *     mariadbd --datadir=<scratch> --port=3398
 *     mariadb -u root -h 127.0.0.1 -P 3398 -e "CREATE DATABASE chip8_shared CHARACTER SET utf8mb4"
 *
 * The database is created if absent and never dropped; every test uses identifiers unique to the run,
 * so a database left behind by an earlier run cannot make one of them pass vacuously. Overridable
 * with -Dessential.test.mysql.host / .port / .user / .password and -Dessential.test.shared.database.
 */
class SharedMariaDbTest {
    private val host = System.getProperty("essential.test.mysql.host", "127.0.0.1")
    private val port = System.getProperty("essential.test.mysql.port", "3398")
    private val user = System.getProperty("essential.test.mysql.user", "root")
    private val pass = System.getProperty("essential.test.mysql.password", "")
    private val database = System.getProperty("essential.test.shared.database", SCRATCH_DATABASE)

    private val log = CopyOnWriteArrayList<String>()
    private var previousLogger: Log.LogHandler? = null

    /**
     * Whether this test got as far as opening the database. The teardown runs even when the assumption
     * above skipped the test, and the globals it closes belong to whichever class ran before this one -
     * on a machine with no MariaDB it would otherwise close that class's database out from under it.
     */
    private var booted = false

    companion object {
        /** Everything these tests put in the shared blob carries this, so the teardown can find it. */
        const val MARK = "c8-"
        const val SCRATCH_DATABASE = "chip8_shared"
    }

    private fun jdbc(db: String) = "jdbc:mariadb://$host:$port/$db?connectTimeout=3000&socketTimeout=30000"

    private fun open(db: String = ""): Connection = DriverManager.getConnection(jdbc(db), user, pass)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.scalar(sql: String): String? =
        createStatement().use { s -> s.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    /** Reads the row the way an operator would, without going through the code under test. */
    private fun stored(uuid: String, column: String): String? =
        open(database).use { it.scalar("SELECT `$column` FROM players WHERE uuid = '$uuid'") }

    private fun requireServer() {
        val failure = runCatching { open().use { it.createStatement().use { s -> s.execute("SELECT 1") } } }
            .exceptionOrNull()
        assumeTrue("no MariaDB at $host:$port, skipping: ${failure?.message}", failure == null)
    }

    @BeforeTest
    fun boot() {
        requireServer()
        open().use { it.exec("CREATE DATABASE IF NOT EXISTS `$database` CHARACTER SET utf8mb4") }

        loadGame(deleteConfig = false)
        // The world-history H2 database is opened at a literal ./config path that nothing creates.
        File("config/mods/Essentials/data").mkdirs()

        // loadGame installs a handler that throws on Log.err. Collect instead: several scenarios below
        // are partly about what the plugin does and does not say, and that has to be readable.
        previousLogger = Log.logger
        Log.logger = Log.LogHandler { _, text -> log += text }

        runBlocking { databaseInit("mariadb://$host:$port/$database", user, pass) }
        booted = true
        assertIs<MariaDBDialect>(
            defaultDatabase!!.config.explicitDialect,
            "the MariaDB branch of databaseInit was not taken, so this proves nothing about MariaDB"
        )
    }

    @AfterTest
    fun close() {
        previousLogger?.let { Log.logger = it }
        previousLogger = null
        log.clear()
        if (!booted) return
        booted = false

        // A blacklisted name filters joins and a temp ban outlives the JVM, so what these tests put in
        // the shared blob comes back out. Removing an element from a PluginData collection publishes a
        // deletion to every server sharing the row, which is why this only touches entries this class
        // marked as its own.
        runCatching {
            runBlocking {
                getPluginData()?.let { row ->
                    val names = row.data.blacklistedNames.removeAll { it.startsWith(MARK) }
                    val warps = row.data.warpCount.removeAll { it.mapName.startsWith(MARK) }
                    val bans = row.data.tempBans.keys.removeAll { it.startsWith(MARK) }
                    if (names || warps || bans) row.update()
                }
            }
        }

        // PluginTest.stopPlugin() sends SHUTDOWN to every open database, which a real MariaDB obeys.
        // Drop the reference first so only the H2 world-history database can be sent that. It is still
        // the right call rather than a bare databaseClose(): it also clears PluginTest's pluginLoaded
        // flag, without which the next class to call loadGame(true) gets no database at all.
        defaultDatabase = null
        stopPlugin()
    }

    private fun uuid(tag: String) = "$MARK$tag-${System.nanoTime()}".take(25)

    /** Closes the database this test opened and opens it again, which is a restart of this instance. */
    private fun reboot() {
        defaultDatabase = null
        databaseClose()
        runBlocking { databaseInit("mariadb://$host:$port/$database", user, pass) }
    }

    private suspend fun freshPlayer(tag: String): Pair<String, PlayerData> {
        val id = uuid(tag)
        val data = createPlayerData(id, id, id, id)
        return id to data
    }

    /**
     * What an instance built at 76444608 does on save: no snapshot, so the generated update() takes
     * its base == null branch and writes every column from the in-memory copy - which is, line for
     * line, the whole body the old generator emitted.
     */
    private fun PlayerData.asOldInstance() = also { it.dbSnapshot = null }

    private fun PluginData.asOldInstance() = also { it.dbSnapshot = null }

    // ---------------------------------------------------------------- scenario 1: shared player row

    @Test
    fun anUnrelatedSaveKeepsEveryColumnAnotherInstanceWrote(): Unit = runBlocking {
        val (id, a) = freshPlayer("s1")

        // Instance B loads its own copy of the row and changes the columns a second server really does
        // change: a ban, a mute, a permission group, a password and an exp award.
        val b = assertNotNull(getPlayerData(id), "instance B could not read the row")
        assertEquals(a.id, b.id, "the two copies must be of the same row")
        b.isBanned = true
        b.chatMuted = true
        b.permission = "admin"
        b.accountPW = "bcrypt-from-b"
        b.exp = 999
        assertTrue(b.update(), "instance B could not write its change")

        // Instance A, still holding the copy it read before any of that, saves for its own reason.
        a.blockPlaceCount = 42
        assertTrue(a.update(), "instance A could not write its own change")

        assertEquals("42", stored(id, "block_place_count"), "instance A's own change was lost")
        assertEquals("1", stored(id, "is_banned"), "the ban instance B wrote was reverted")
        assertEquals("1", stored(id, "chat_muted"), "the mute instance B wrote was reverted")
        assertEquals("admin", stored(id, "permission"), "the group instance B wrote was reverted")
        assertEquals("bcrypt-from-b", stored(id, "account_pw"), "the password instance B wrote was reverted")
        assertEquals("999", stored(id, "exp"), "the exp instance B wrote was reverted")
    }

    /**
     * The negative control for the test above, and the mixed-version case of scenario 4 in one: an
     * instance with no snapshot writes the row whole, which is what every server did before 23351b7a
     * and what an old jar in a staggered rollout still does.
     */
    @Test
    fun anInstanceWithoutASnapshotRevertsEveryColumnAnotherInstanceWrote(): Unit = runBlocking {
        val (id, a) = freshPlayer("s1inv")

        val b = assertNotNull(getPlayerData(id), "instance B could not read the row")
        b.isBanned = true
        b.permission = "admin"
        b.exp = 999
        assertTrue(b.update(), "instance B could not write its change")

        a.asOldInstance()
        a.blockPlaceCount = 42
        a.update()

        assertEquals("42", stored(id, "block_place_count"), "the old-shaped write did not land at all")
        assertEquals(
            listOf("0", "default", "0"),
            listOf(stored(id, "is_banned"), stored(id, "permission"), stored(id, "exp")),
            "a whole-row write did NOT revert the other instance's columns, so the test above proves nothing"
        )
        assertTrue(
            log.none { it.contains(id) },
            "expected the revert to be silent; it logged: ${log.filter { it.contains(id) }}"
        )
    }

    // ------------------------------------------------------------- scenario 2: shared plugin_data

    @Test
    fun twoInstancesEditingTheSharedBlobBothSurvive(): Unit = runBlocking {
        val name = "${MARK}name-${System.nanoTime()}"
        val banned = "${MARK}ban-${System.nanoTime()}"

        val a = assertNotNull(getPluginData() ?: createPluginData(), "instance A could not read plugin_data")
        val b = assertNotNull(getPluginData(), "instance B could not read plugin_data")
        assertEquals(a.id, b.id, "the two copies must be of the same row")
        assertTrue(a.data !== b.data, "the two copies must not share the blob")

        // A adds to the name blacklist; B issues a temp ban. Neither has seen the other's change.
        a.data.blacklistedNames.add(name)
        b.data.tempBans[banned] = "2099-01-01T00:00"

        assertTrue(b.update(), "instance B could not write the temp ban")
        assertTrue(a.update(), "instance A could not write its own change")

        val storedRow = assertNotNull(getPluginData(), "plugin_data disappeared")
        assertTrue(storedRow.data.blacklistedNames.contains(name), "instance A's own change was lost")
        assertTrue(storedRow.data.tempBans.containsKey(banned), "instance A's save erased instance B's temp ban")
    }

    @Test
    fun anInstanceWithoutASnapshotOverwritesTheWholeSharedBlob(): Unit = runBlocking {
        val name = "${MARK}name-${System.nanoTime()}"
        val banned = "${MARK}ban-${System.nanoTime()}"

        val a = assertNotNull(getPluginData() ?: createPluginData(), "instance A could not read plugin_data")
        val b = assertNotNull(getPluginData(), "instance B could not read plugin_data")

        b.data.tempBans[banned] = "2099-01-01T00:00"
        assertTrue(b.update(), "instance B could not write the temp ban")

        a.asOldInstance()
        a.data.blacklistedNames.add(name)
        a.update()

        val storedRow = assertNotNull(getPluginData(), "plugin_data disappeared")
        assertTrue(storedRow.data.blacklistedNames.contains(name), "the old-shaped write did not land at all")
        assertFalse(
            storedRow.data.tempBans.containsKey(banned),
            "a whole-blob write did NOT erase the other instance's temp ban, so the test above proves nothing"
        )
        assertTrue(
            log.none { it.contains(banned) },
            "expected the erasure to be silent; it logged: ${log.filter { it.contains(banned) }}"
        )
    }

    // ----------------------------------------------------------- scenario 3: WarpCount duplicates

    /**
     * Two servers on a same-named hub map, each refreshing the live player count on its own warp.
     *
     * WarpCount.players is a constructor property, so it is part of equals, and mergedOnto identifies
     * elements by equals. Every refresh therefore reads as a delete of the old element plus an add of
     * the new one, and the delete is computed against a snapshot the other server has already moved
     * past - so the other server's element is left behind and a new one is added beside it.
     * WarpBlock.online is a body property, four lines away in the same file, and is safe.
     */
    @Test
    fun refreshingAWarpCountOnTwoInstancesDoesNotAccumulateRows(): Unit = runBlocking {
        val map = "${MARK}hub-${System.nanoTime()}"

        val seed = assertNotNull(getPluginData() ?: createPluginData(), "could not read plugin_data")
        seed.data.warpCount.add(WarpCount(map, 100, "127.0.0.1", 6567).also { it.players = 1; it.numberSize = 3 })
        assertTrue(seed.update(), "could not seed the warp count")

        val a = assertNotNull(getPluginData(), "instance A could not read plugin_data")
        val b = assertNotNull(getPluginData(), "instance B could not read plugin_data")

        // Six refreshes, alternating servers, which on a hub is a few seconds of ordinary operation.
        for (round in 1..3) {
            a.data.warpCount.first { it.mapName == map }.players = round * 2
            assertTrue(a.update(), "instance A could not refresh its count")
            b.data.warpCount.first { it.mapName == map }.players = round * 2 + 1
            assertTrue(b.update(), "instance B could not refresh its count")
        }

        val rows = assertNotNull(getPluginData(), "plugin_data disappeared").data.warpCount.filter { it.mapName == map }
        assertEquals(
            1, rows.size,
            "one warp on one map became ${rows.size} rows in the shared blob after six refreshes: " +
                rows.map { it.players }
        )
    }

    // ------------------------------------------------------- scenario 7: two instances, empty database

    /**
     * Two instances booting against an empty database at the same moment. createPluginData re-checks
     * inside its transaction, which narrows the window without closing it: nothing in the schema stops
     * a second row. A second row is not cosmetic - getPluginData returns the oldest, but
     * PluginData.update writes and merges by id, so an instance that created the second row keeps
     * reading the first and writing the second for as long as it runs.
     */
    @Test
    fun twoInstancesStartingAtOnceDoNotEachInsertAPluginDataRow(): Unit = runBlocking {
        // The only destructive statement in this class, and plugin_data on a real server holds its warp
        // zones, its name blacklist and every active temp ban. It runs against the scratch database this
        // chip created and nowhere else.
        assumeTrue(
            "plugin_data is only emptied in the scratch database, not in $database",
            database == SCRATCH_DATABASE
        )
        open(database).use { it.exec("DELETE FROM plugin_data") }

        val results = withContext(Dispatchers.IO) {
            (1..2).map { async { runCatching { getPluginData() ?: createPluginData() }.getOrNull()?.id } }.awaitAll()
        }

        val count = suspendTransaction { PluginTable.selectAll().count() }
        assertEquals(
            1L, count,
            "a simultaneous start left $count plugin_data rows; the instances took ids $results, and an " +
                "instance holding the second one writes a row every other server reads past"
        )
    }

    /**
     * A player joining two servers at the same instant. players.uuid and players.name both carry a
     * unique index, so one of the two inserts is refused - and this is the test that measures what the
     * loser does with that.
     *
     * It used to assert that the loser logged "Failed to load player data": createPlayerData let the
     * constraint violation out, loadJoinedPlayerData (CoreEvent.kt:620-635) caught it and returned a
     * null data object, and retryPlayerDataLoad (CoreEvent.kt:677-706) picked the real row up within
     * ten seconds. That was the defect, not the contract - for those ten seconds the session was on
     * temporary data. createPlayerData now treats the refusal as the row-already-exists case it is and
     * re-reads the winner's row, so both instances come away with data and there is nothing to log and
     * nothing to retry. The assertion is now that neither instance fell back.
     */
    @Test
    fun aPlayerJoiningTwoInstancesAtOnceSaysSoAndKeepsOneRow(): Unit = runBlocking {
        // The whole of the repair under test is the engine refusing the second insert. Earlier work
        // measured that a schema the legacy scripts built carries none of the unique indexes the
        // Kotlin tables declare - which is what the six live servers are running. So say which of the
        // two worlds this run is in, from the engine's own catalogue, rather than passing or failing
        // according to whatever shape `$database` happens to have been left in.
        val uniqueOnUuid = open(database).use {
            it.scalar(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = '$database' " +
                    "AND table_name = 'players' AND column_name = 'uuid' AND non_unique = 0"
            )
        }
        assertEquals(
            "1", uniqueOnUuid,
            "players.uuid carries no unique index in `$database` on $host:$port, so nothing refuses a " +
                "second insert and this test would prove nothing about what the loser of a race does. " +
                "A schema built by resources/sql rather than by SchemaUtils looks exactly like this."
        )

        val player = createPlayer()
        val id = player.uuid()
        try {
            val results = withContext(Dispatchers.IO) {
                (1..2).map { async { loadJoinedPlayerData(player, player.name()) } }.awaitAll()
            }

            val rows = open(database).use { it.scalar("SELECT COUNT(*) FROM players WHERE uuid = '$id'") }
            assertEquals("1", rows, "a simultaneous join left $rows rows for one uuid")
            assertNotNull(getPlayerData(id), "neither instance ended up with a row")
            assertTrue(
                results.all { it.data != null },
                "an instance fell back to temporary data over a row that was already in the table, so " +
                    "nothing from that session would be saved until the ten-second retry caught up"
            )
            assertTrue(
                log.none { it.contains("Failed to load player data") },
                "the loser of the insert race still reported a failure: " +
                    log.filter { it.contains("Failed to load player data") }
            )
        } finally {
            player.remove()
            Groups.player.update()
        }
    }

    /** A blob element a server merely does not have locally is published as a deletion to the others. */
    @Test
    fun anInstanceThatNeverSawAWarpDoesNotDeleteIt(): Unit = runBlocking {
        val map = "${MARK}hub-${System.nanoTime()}"

        val a = assertNotNull(getPluginData() ?: createPluginData(), "instance A could not read plugin_data")
        val b = assertNotNull(getPluginData(), "instance B could not read plugin_data")

        b.data.warpCount.add(WarpCount(map, 200, "127.0.0.1", 6567).also { it.players = 1; it.numberSize = 3 })
        assertTrue(b.update(), "instance B could not add its warp")

        // A's blob is the one it booted with, and it saves for an unrelated reason.
        a.data.blacklistedNames.add("${MARK}unrelated-${System.nanoTime()}")
        assertTrue(a.update(), "instance A could not write its own change")

        val rows = assertNotNull(getPluginData(), "plugin_data disappeared").data.warpCount.filter { it.mapName == map }
        assertEquals(1, rows.size, "instance A's unrelated save deleted a warp it had never seen")
    }

    /** An instance whose own blob is empty must not be the thing that resets a shared row. */
    @Test
    fun anInstanceWithAnEmptyBlobDoesNotResetTheSharedOne(): Unit = runBlocking {
        val name = "${MARK}keep-${System.nanoTime()}"
        val b = assertNotNull(getPluginData() ?: createPluginData(), "instance B could not read plugin_data")
        b.data.blacklistedNames.add(name)
        assertTrue(b.update(), "instance B could not write")

        val a = assertNotNull(getPluginData(), "instance A could not read plugin_data")
        a.data = DisplayData()
        a.dbSnapshot = a.dbSnapshot?.copy(data = DisplayData())
        assertTrue(a.update(), "instance A could not write")

        val storedRow = assertNotNull(getPluginData(), "plugin_data disappeared")
        assertTrue(
            storedRow.data.blacklistedNames.contains(name),
            "an instance with an empty blob erased what another instance had written"
        )
    }

    /**
     * An instance that dies without running its dispose listener leaves is_connected true, and nothing
     * anywhere sets it back: every writer of that column (CoreEvent.kt:270, :304, :1066, Trigger.kt:641,
     * :748, Main.kt:172) runs on the server the player is on, at leave, transfer, AFK or shutdown, and
     * no boot reconciles it. /login refuses an account whose row says connected
     * (service/protect/Commands.kt:69), so the account stays locked on all six servers.
     */
    @Test
    fun anInstanceThatDiedDoesNotLeaveAnAccountLockedOut(): Unit = runBlocking {
        val (id, a) = freshPlayer("s7dead")
        a.isConnected = true
        assertTrue(a.update(), "could not mark the player connected")

        // Instance A is gone. Another instance restarts; a real deploy restarts all six.
        reboot()

        assertEquals(
            "0", stored(id, "is_connected"),
            "a player left connected by an instance that died is still marked connected after another " +
                "instance restarted, and /login refuses an account whose row says connected"
        )
    }

    /**
     * Two instances shutting down at the same moment. Main.kt:166-181 writes isConnected and
     * lastLogoutDate for each of its own players; with the changed-column write those are the only two
     * columns that move, so a shutdown cannot revert anything a sibling wrote on its way down.
     */
    @Test
    fun twoInstancesShuttingDownAtOnceDoNotRevertEachOther(): Unit = runBlocking {
        val (id, a) = freshPlayer("s7down")
        a.exp = 555
        assertTrue(a.update(), "could not set up the row")

        val b = assertNotNull(getPlayerData(id), "instance B could not read the row")
        b.permission = "admin"
        assertTrue(b.update(), "instance B could not write its own shutdown-time change")

        // A's dispose listener, from the copy it has held since the player joined.
        a.isConnected = false
        a.lastLogoutDate = kotlinx.datetime.LocalDateTime.parse("2026-01-01T00:00")
        assertTrue(a.update(), "instance A could not write its shutdown")

        assertEquals("555", stored(id, "exp"), "a shutdown reverted an unrelated column")
        assertEquals("admin", stored(id, "permission"), "a shutdown reverted what the other instance wrote")
        assertEquals("0", stored(id, "is_connected"), "the shutdown's own write did not land")
    }
}
