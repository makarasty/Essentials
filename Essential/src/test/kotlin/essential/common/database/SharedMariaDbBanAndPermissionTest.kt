package essential.common.database

import PluginTest.Companion.loadGame
import PluginTest.Companion.pumpApp
import PluginTest.Companion.stopPlugin
import PluginTest.Companion.waitUntil
import arc.util.Log
import essential.common.database.data.PluginData
import essential.common.database.data.checkPlayerBanned
import essential.common.database.data.createBanInfo
import essential.common.database.data.createPlayerData
import essential.common.database.data.createPluginData
import essential.common.database.data.getPlayerData
import essential.common.database.data.getPluginData
import essential.common.database.data.removeBanInfoByUUID
import essential.common.database.table.PlayerBannedTable
import essential.common.permission.Permission
import essential.common.pluginData
import essential.common.rootPath
import essential.core.TempBan
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import mindustry.Vars
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.Assume.assumeTrue
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Bans, ban expiry and permissions with two instances on one shared MariaDB.
 *
 * The ban list and the permission file are per-server state; the ban table, the expiry column and the
 * permission column are shared. Everything below is about what one server's local action does to the
 * five rows the other five servers read.
 *
 * Skipped by an assumption when no server answers. See [SharedMariaDbTest] for how to start one.
 */
@OptIn(ExperimentalTime::class)
class SharedMariaDbBanAndPermissionTest {
    private val host = System.getProperty("essential.test.mysql.host", "127.0.0.1")
    private val port = System.getProperty("essential.test.mysql.port", "3398")
    private val user = System.getProperty("essential.test.mysql.user", "root")
    private val pass = System.getProperty("essential.test.mysql.password", "")
    private val database = System.getProperty("essential.test.shared.database", "chip8_shared")

    private var previousLogger: Log.LogHandler? = null
    private var permissionFileBackup: String? = null
    private val bannedHere = mutableListOf<String>()
    private var previousPluginData: PluginData? = null

    /**
     * Whether this test got as far as opening the database. The teardown runs even when the assumption
     * below skipped the test, and the globals it closes belong to whichever class ran before this one.
     */
    private var booted = false

    private fun open(db: String = ""): Connection = DriverManager.getConnection(
        "jdbc:mariadb://$host:$port/$db?connectTimeout=3000&socketTimeout=30000", user, pass
    )

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.scalar(sql: String): String? =
        createStatement().use { s -> s.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    private fun storedGroup(uuid: String): String? =
        open(database).use { it.scalar("SELECT permission FROM players WHERE uuid = '$uuid'") }

    private fun requireServer() {
        val failure = runCatching { open().use { it.createStatement().use { s -> s.execute("SELECT 1") } } }
            .exceptionOrNull()
        assumeTrue("no MariaDB at $host:$port, skipping: ${failure?.message}", failure == null)
    }

    private val userFile get() = rootPath.child("permission_user.yaml")

    @BeforeTest
    fun boot() {
        requireServer()
        open().use { it.exec("CREATE DATABASE IF NOT EXISTS `$database` CHARACTER SET utf8mb4") }

        loadGame(deleteConfig = false)
        File("config/mods/Essentials/data").mkdirs()

        previousLogger = Log.logger
        Log.logger = Log.LogHandler { _, _ -> }

        runBlocking {
            databaseInit("mariadb://$host:$port/$database", user, pass)
            booted = true
            // TempBan reads the shared blob for the temp bans that never reached a player row, and the
            // plugin sets this at boot. Nothing here loads the plugin, so set it from the same row - and
            // keep whatever was there, because it is a process global every later class reads.
            previousPluginData = runCatching { pluginData }.getOrNull()
            pluginData = getPluginData() ?: createPluginData()
        }
    }

    @AfterTest
    fun close() {
        // First, before anything that can throw: a real MariaDB obeys the SHUTDOWN that
        // PluginTest.stopPlugin() sends to every open database, and dropping the reference here is what
        // keeps that SHUTDOWN off the operator's server for the rest of the run.
        defaultDatabase = null

        permissionFileBackup?.let {
            userFile.writeString(it, false)
            Permission.load()
            // load() ends in apply(), which posts its work to the application queue. Run it now rather
            // than leaving it to fire during some later class's pump, against that class's database.
            pumpApp()
        }
        permissionFileBackup = null

        // Vars.netServer.admins is a process global that Core.settings autosaves to disk, so a ban left
        // here outlives the JVM as well as the class.
        bannedHere.forEach { runCatching { Vars.netServer.admins.unbanPlayerID(it) } }
        bannedHere.clear()

        previousLogger?.let { Log.logger = it }
        previousLogger = null

        if (!booted) return
        booted = false

        runCatching {
            runBlocking {
                getPluginData()?.let { row ->
                    if (row.data.tempBans.keys.removeAll { it.startsWith(SharedMariaDbTest.MARK) }) row.update()
                }
            }
        }
        previousPluginData?.let { pluginData = it }
        previousPluginData = null

        // stopPlugin() rather than a bare databaseClose(): it also clears PluginTest's pluginLoaded
        // flag, without which the next class to call loadGame(true) gets no database at all.
        stopPlugin()
    }

    private fun uuid(tag: String) = "${SharedMariaDbTest.MARK}$tag-${System.nanoTime()}".take(25)

    private fun past() = LocalDateTime.parse("2000-01-01T00:00")


    private suspend fun banRows(uuid: String): Long =
        suspendTransaction { PlayerBannedTable.selectAll().where { PlayerBannedTable.uuid eq uuid }.count() }

    // ------------------------------------------------------------------- scenario 5: bans and expiry

    /** A ban placed on one instance has to be what the next instance's connect check reads. */
    @Test
    fun aBanOnOneInstanceIsVisibleToTheOthersConnectCheck(): Unit = runBlocking {
        val id = uuid("s5see")
        createPlayerData(id, id, id, id)

        val info = Vars.netServer.admins.getInfo(id)
        info.lastName = id
        info.names.add(id)
        info.ips.add("10.0.0.1")
        createBanInfo(info, "banned on instance A")

        assertTrue(
            checkPlayerBanned(id, "10.0.0.2", "some other name"),
            "instance B's connect check did not see a ban instance A wrote to the shared table"
        )
    }

    /**
     * Two instances each ban the same player - a temp ban on A, a permanent one on B - and the shared
     * table holds a row for each. When A's temp ban expires its unban handler calls
     * removeBanInfoByUUID, which deletes by uuid alone, so B's row goes with it and the four servers
     * that were not involved stop refusing the player on connect. B goes on refusing them from its own
     * local ban list, which is why nobody on B would notice.
     */
    @Test
    fun anUnbanOnOneInstanceDoesNotDeleteAnotherInstancesBan(): Unit = runBlocking {
        val id = uuid("s5two")
        createPlayerData(id, id, id, id)

        val info = Vars.netServer.admins.getInfo(id)
        info.lastName = id
        createBanInfo(info, "temp ban issued on instance A")
        createBanInfo(info, "permanent ban issued on instance B")
        assertEquals(2L, banRows(id), "the two instances should each have written a ban row")

        // What CoreEvent's PlayerUnbanEvent handler runs when A's sweep lifts its own expired ban.
        removeBanInfoByUUID(id)

        assertTrue(
            banRows(id) > 0,
            "one instance lifting its own temp ban deleted every ban row for that player, including " +
                "the permanent ban another instance issued; the shared table is what the other servers " +
                "read on connect, so the player is let back in everywhere but the instance that banned them"
        )
        assertTrue(
            checkPlayerBanned(id, "10.0.0.9", "another name"),
            "after one instance's unban the connect check no longer sees the other instance's ban"
        )
    }

    /**
     * A temp ban that never reached a player row lives in the shared blob. It has to be lifted once,
     * and an instance still holding the blob it read before the lift must not put it back when it saves
     * for its own reasons.
     */
    @Test
    fun anExpiredTempBanIsLiftedOnceAndIsNotResurrectedByTheOtherInstance(): Unit = runBlocking {
        val id = uuid("s5exp")
        val admins = Vars.netServer.admins

        pluginData.data.tempBans[id] = past().toString()
        assertTrue(pluginData.update(), "instance A could not write the temp ban")
        admins.banPlayerID(id)
        bannedHere += id

        // Instance B reads the blob while the ban is still live and holds it.
        val b = assertNotNull(getPluginData(), "instance B could not read plugin_data")
        assertTrue(b.data.tempBans.containsKey(id), "instance B has to hold the ban for this to prove anything")

        TempBan.tick(setOf(id))
        pumpApp()
        assertFalse(admins.isIDBanned(id), "the sweep did not lift an expired temp ban")
        assertFalse(
            assertNotNull(getPluginData()).data.tempBans.containsKey(id),
            "the sweep lifted the ban but left it in the shared blob, so every other server still holds it"
        )

        // Instance B saves for an unrelated reason, still carrying the lifted ban in its own copy.
        b.data.blacklistedNames.add("unrelated-${System.nanoTime()}")
        assertTrue(b.update(), "instance B could not write its own change")

        assertFalse(
            assertNotNull(getPluginData()).data.tempBans.containsKey(id),
            "instance B's unrelated save resurrected a temp ban that had already been lifted"
        )

        // A second sweep must find nothing left to lift.
        TempBan.tick(setOf(id))
        pumpApp()
        assertFalse(admins.isIDBanned(id), "a second sweep re-banned or failed to leave the player unbanned")
    }

    /**
     * permaban landing in the window between the sweep queueing an unban and that unban running.
     *
     * The sweep does `lifting += expired` and then posts the unbans to a later frame. permaban clears
     * the expiry, re-reads the row, finds it null and reports the ban permanent - and the queued unban
     * lifts it anyway. Driven here rather than raced, because the window is one frame wide.
     */
    @Test
    fun aPermabanDuringTheSweepWindowKeepsTheBan(): Unit = runBlocking {
        val id = uuid("s5race")
        val admins = Vars.netServer.admins
        createPlayerData(id, id, id, id)

        admins.banPlayerID(id)
        bannedHere += id
        TempBan.setBanExpire(id, past())
        assertTrue(admins.isIDBanned(id), "the ban this test is about should be in place")

        TempBan.tick(setOf(id))
        // permaban's own body: clear the expiry, and nothing else.
        TempBan.clearBanExpire(id)
        pumpApp()

        assertTrue(
            admins.isIDBanned(id),
            "a permaban that landed after the sweep queued its unban was lifted by that queued unban " +
                "anyway, and permaban had already reported the ban permanent"
        )

        // The trap in the fix: a uuid the guard skips must have left the set, so that the next genuine
        // unban is still recorded as one.
        TempBan.setBanExpire(id, past())
        TempBan.onUnban(id)
        assertNull(
            assertNotNull(getPlayerData(id)).banExpireDate,
            "a stale token left in the lifting set made the next human unban forget to clear the expiry"
        )
    }

    // -------------------------------------------------------------------- scenario 6: permissions

    /** setperm writes the shared row and nothing local, so the other instances read it on their next look. */
    @Test
    fun aGroupWrittenOnOneInstanceIsVisibleOnTheOther(): Unit = runBlocking {
        val id = uuid("s6see")
        val a = createPlayerData(id, id, id, id)

        a.permission = "admin"
        assertTrue(a.update(), "instance A could not write the group")

        assertEquals(
            "admin", assertNotNull(getPlayerData(id), "instance B could not read the row").permission,
            "instance B did not see the group instance A wrote to the shared row"
        )
    }

    /**
     * What happens when both are set and disagree, stated so an operator can plan around it: the file
     * wins, and it does not win only locally. Permission.load() ends in apply(), which writes every
     * entry in the file over the shared column for any player not currently online - so an instance
     * with a hand-written entry pushes its group onto the other five servers at every start and every
     * /reload, and a setperm made elsewhere in between is gone.
     */
    @Test
    fun whenTheFileAndTheSharedColumnDisagreeTheFileWinsForEveryInstance(): Unit = runBlocking {
        val id = uuid("s6both")
        val a = createPlayerData(id, id, id, id)

        permissionFileBackup = if (userFile.exists()) userFile.readString() else ""
        userFile.writeString("$id:\n    group: \"owner\"\n", false)
        Permission.load()

        a.permission = "visitor"
        assertTrue(a.update(), "instance A could not write the group")
        assertEquals("visitor", storedGroup(id), "instance A's write did not reach the shared row")

        // Instance B restarts, or an operator runs /reload on it.
        Permission.load()

        assertTrue(
            waitUntil(10000, 200) { storedGroup(id) == "owner" },
            "the reload did not push the file's group onto the shared row; the shared row reads " +
                "${storedGroup(id)}"
        )
    }

    /** The same file, applied while the player is offline, must not disturb the rest of their row. */
    @Test
    fun applyingThePermissionFileTouchesNothingButTheGroup(): Unit = runBlocking {
        val id = uuid("s6only")
        val a = createPlayerData(id, id, id, id)
        a.exp = 4321
        a.isBanned = true
        assertTrue(a.update(), "could not set up the row")

        permissionFileBackup = if (userFile.exists()) userFile.readString() else ""
        userFile.writeString("$id:\n    group: \"owner\"\n", false)
        Permission.load()
        assertTrue(waitUntil(10000, 200) { storedGroup(id) == "owner" }, "the file's group did not reach the shared row")

        val stored = assertNotNull(getPlayerData(id), "the row disappeared")
        assertEquals(4321, stored.exp, "applying the permission file reverted an unrelated column")
        assertTrue(stored.isBanned, "applying the permission file reverted a ban")
    }
}
