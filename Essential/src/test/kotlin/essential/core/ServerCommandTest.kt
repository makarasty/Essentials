package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.newPlayer
import PluginTest.Companion.serverCommand
import PluginTest.Companion.waitUntil
import arc.Events
import arc.util.Log
import essential.common.bundle.Bundle
import essential.common.database.data.createTemporaryPlayerData
import essential.common.database.data.getPlayerData
import essential.common.database.data.setAchievement
import essential.common.database.data.update
import essential.common.database.table.AchievementTable
import essential.common.permission.Permission
import essential.common.players
import essential.common.pluginData
import essential.common.rootPath
import essential.common.util.PlayerLookup
import essential.common.systemTimezone
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Groups
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

class ServerCommandTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)

            done = true
        }
    }

    @Test
    fun server_mergePlayer() {
        val target = newPlayer()
        val dest = newPlayer()

        runBlocking {
            target.second.exp = 100000
            dest.second.exp = 100000
            target.second.update()
            dest.second.update()
        }

        assertTrue(
            waitUntil(10000) {
                runBlocking {
                    getPlayerData(target.first.uuid())?.exp == 100000 &&
                        getPlayerData(dest.first.uuid())?.exp == 100000
                }
            },
            "Player exp should be persisted before merge"
        )

        serverCommand.handleMessage("mergeplayer ${target.first.uuid()} ${dest.first.uuid()}")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(dest.first.uuid())?.exp == 200000 } },
            "Merged exp should be 200000 but was ${runBlocking { getPlayerData(dest.first.uuid())?.exp }}"
        )
    }

    @Test
    fun server_deletePlayerByUuid() {
        val target = newPlayer()
        runBlocking {
            setAchievement(target.second, "test_achievement")
        }
        Events.fire(EventType.PlayerLeave(target.first))

        val uuid = target.first.uuid()
        val beforeDelete = runBlocking { getPlayerData(uuid) }
        assertNotNull(beforeDelete)

        serverCommand.handleMessage("delete $uuid")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid) } == null },
            "Player should be deleted"
        )

        val achievementsCount = runBlocking {
            suspendTransaction {
                AchievementTable.selectAll().where { AchievementTable.playerId eq target.second.id }.count()
            }
        }
        assertEquals(0, achievementsCount)
    }

    @Test
    fun server_deletePlayerById() {
        val target = newPlayer()
        Events.fire(EventType.PlayerLeave(target.first))

        val uuid = target.first.uuid()
        val beforeDelete = runBlocking { getPlayerData(uuid) }
        assertNotNull(beforeDelete)
        val id = target.second.id

        serverCommand.handleMessage("delete $id")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid) } == null },
            "Player should be deleted by id"
        )
    }

    @Test
    fun server_deletePlayerByName_multiple() {
        val target1 = newPlayer()
        val target2 = newPlayer()

        runBlocking {
            target1.second.name = "multipleplayer"
            target2.second.name = "MULTIPLEPLAYER"
            target1.second.update()
            target2.second.update()
        }

        serverCommand.handleMessage("delete multipleplayer")

        assertFalse(
            waitUntil(2000) { runBlocking { getPlayerData(target1.first.uuid()) } == null },
            "Ambiguous name should not delete anyone"
        )
        assertNotNull(runBlocking { getPlayerData(target2.first.uuid()) })

        serverCommand.handleMessage("delete ${target1.second.id}")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(target1.first.uuid()) } == null },
            "First player should be deleted by id"
        )
        assertNotNull(runBlocking { getPlayerData(target2.first.uuid()) })

        serverCommand.handleMessage("delete ${target2.second.id}")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(target2.first.uuid()) } == null },
            "Second player should be deleted by id"
        )
    }

    @Test
    fun server_setPermOfflineByUuid() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        leavePlayer(target.first)

        serverCommand.handleMessage("setperm $uuid admin")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.permission } == "admin" },
            "the offline group change should land"
        )
        assertContains(rootPath.child("permission_user.yaml").readString(), uuid)

        val rejoin = createPlayer()
        rejoin.con.uuid = uuid
        val data = joinPlayer(rejoin)

        assertEquals("admin", data.permission)
        assertTrue(rejoin.admin(), "Group admin flag should be applied on join")

        leavePlayer(rejoin)
    }

    @Test
    fun server_setPermOfflineByName() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val name = target.first.name()
        leavePlayer(target.first)

        serverCommand.handleMessage("setperm $name owner")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.permission } == "owner" },
            "the offline group change should land"
        )
    }

    @Test
    fun server_perm() {
        val target = newPlayer()
        val uuid = target.first.uuid()

        serverCommand.handleMessage("setperm $uuid admin")

        val lines = mutableListOf<String>()
        val previous = Log.logger
        Log.logger = Log.LogHandler { level, text ->
            previous.log(level, text)
            lines.add(text)
        }
        try {
            serverCommand.handleMessage("perm $uuid")
        } finally {
            Log.logger = previous
        }

        assertTrue(
            lines.any { it.contains(uuid) && it.contains("admin") && it.contains("true") },
            "perm output should show the group and admin flag but was $lines"
        )

        leavePlayer(target.first)
    }

    @Test
    fun server_permNotFound() {
        val lines = mutableListOf<String>()
        val previous = Log.logger
        Log.logger = Log.LogHandler { level, text ->
            previous.log(level, text)
            lines.add(text)
        }
        try {
            serverCommand.handleMessage("perm nobody-here")
            assertTrue(
                waitUntil(10000) { lines.any { it.contains(Bundle()["player.not.found"]) } },
                "perm should report a missing player but was $lines"
            )
        } finally {
            Log.logger = previous
        }
    }

    private fun captureLog(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val previous = Log.logger
        Log.logger = Log.LogHandler { level, text ->
            previous.log(level, text)
            lines.add(text)
        }
        try {
            block()
        } finally {
            Log.logger = previous
        }
        return lines
    }

    @Test
    fun server_setPermRejectsUnknownGroup() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val before = Permission.groupOf(uuid, target.second.permission)

        val lines = captureLog { serverCommand.handleMessage("setperm $uuid nonexistent") }

        assertEquals(
            before,
            Permission.groupOf(uuid, target.second.permission),
            "an unknown group must not be applied"
        )
        assertTrue(
            lines.any { it.contains("nonexistent") && it.contains("admin") },
            "the reply should name the valid groups but was $lines"
        )

        leavePlayer(target.first)
    }

    @Test
    fun server_setPermRefusesWhenUserFileIsBroken() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val file = rootPath.child("permission_user.yaml")
        val good = file.readString()
        val broken = "broken-entry:\n    group: [unclosed\n"

        file.writeString(broken, false)
        Permission.load()

        val lines = captureLog { serverCommand.handleMessage("setperm $uuid admin") }

        assertEquals(broken, file.readString(), "a file that failed to parse must be left untouched")
        assertTrue(
            lines.any { it.contains("permission_user.yaml") },
            "the admin should be told why the write was refused but was $lines"
        )

        file.writeString(good, false)
        Permission.load()
        leavePlayer(target.first)
    }

    @Test
    fun server_setPermBacksUpAndKeepsUnknownKeys() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val file = rootPath.child("permission_user.yaml")
        val backup = rootPath.child("permission_user.yaml.bak")
        val good = file.readString()

        file.writeString("$uuid:\n    group: \"user\"\n    customField: \"keep me\"\n", false)
        Permission.load()
        backup.delete()

        serverCommand.handleMessage("setperm $uuid admin")

        val written = file.readString()
        assertTrue(backup.exists(), "the previous file should be kept as permission_user.yaml.bak")
        assertContains(written, "customField", message = "an unknown key must survive a write")
        assertContains(written, "keep me", message = "an unknown value must survive a write")
        assertEquals("admin", Permission.groupOf(uuid, "user"), "the group should still be patched")

        file.writeString(good, false)
        Permission.load()
        leavePlayer(target.first)
    }

    @Test
    fun server_setPermQueuesOfflineTarget() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val name = target.second.name
        leavePlayer(target.first)

        val bundle = Bundle()
        val lines = mutableListOf<String>()
        val previous = Log.logger
        Log.logger = Log.LogHandler { level, text ->
            previous.log(level, text)
            lines.add(text)
        }
        try {
            serverCommand.handleMessage("setperm $uuid admin")
            assertTrue(
                lines.any { it == bundle["command.setPerm.queued", PlayerLookup.shortName(uuid)] },
                "the queued line must be printed before the command returns but was $lines"
            )
            assertTrue(
                waitUntil(10000) { lines.any { it == bundle["command.setPerm.success", name, "admin"] } },
                "the result line should follow but was $lines"
            )
        } finally {
            Log.logger = previous
        }
    }

    @Test
    fun server_setPermSyncsVanillaAdmin() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val admins = Vars.netServer.admins

        serverCommand.handleMessage("setperm $uuid admin")

        assertTrue(admins.getInfo(uuid).admin, "setperm admin should raise the vanilla admin flag")
        assertTrue(admins.isAdmin(uuid, target.first.usid()), "the stored admin usid should match the session")

        serverCommand.handleMessage("setperm $uuid user")

        assertFalse(admins.getInfo(uuid).admin, "setperm user should clear the vanilla admin flag")

        leavePlayer(target.first)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun server_tempBanCreatesVanillaBanAndExpires() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val admins = Vars.netServer.admins

        serverCommand.handleMessage("tempban $uuid 10 test reason")

        assertTrue(waitUntil(10000) { admins.isIDBanned(uuid) }, "tempban should create a vanilla ban")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } != null },
            "tempban should store the ban expiry"
        )

        runBlocking {
            val data = getPlayerData(uuid)!!
            data.banExpireDate = Clock.System.now().minus(1.minutes).toLocalDateTime(systemTimezone)
            data.update()
            TempBan.tick()
        }

        assertFalse(admins.isIDBanned(uuid), "the scheduler should lift an expired ban")
        assertNull(runBlocking { getPlayerData(uuid)?.banExpireDate }, "the scheduler should clear the ban expiry")
    }

    @Test
    fun server_unbanClearsBanExpire() {
        val target = newPlayer()
        val uuid = target.first.uuid()

        serverCommand.handleMessage("tempban $uuid 10 test reason")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } != null },
            "tempban should store the ban expiry"
        )

        serverCommand.handleMessage("unban $uuid")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } == null },
            "unban should clear the ban expiry"
        )
        assertFalse(Vars.netServer.admins.isIDBanned(uuid), "unban should lift the vanilla ban")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun server_tempBanWithoutPlayerRow() {
        val uuid = "orphan" + Clock.System.now().toEpochMilliseconds()
        val admins = Vars.netServer.admins
        admins.getInfo(uuid)

        serverCommand.handleMessage("tempban $uuid 10 test reason")

        assertTrue(admins.isIDBanned(uuid), "an unregistered uuid should still get a vanilla ban")
        assertTrue(
            waitUntil(10000) { pluginData.data.tempBans.containsKey(uuid) },
            "the expiry of an unregistered uuid should be kept in the plugin data"
        )

        pluginData.data.tempBans[uuid] =
            Clock.System.now().minus(1.minutes).toLocalDateTime(systemTimezone).toString()
        runBlocking { TempBan.tick() }

        assertFalse(admins.isIDBanned(uuid), "the scheduler should lift an expired ban without a player row")
        assertFalse(pluginData.data.tempBans.containsKey(uuid), "the scheduler should drop the stored expiry")
    }

    @Test
    fun server_tempBanOnlineTemporaryPlayer() {
        val target = createPlayer()
        target.name("slxtemporary")
        val data = createTemporaryPlayerData(target)
        data.temporary = true
        players.add(data)

        val uuid = target.uuid()
        val admins = Vars.netServer.admins

        try {
            serverCommand.handleMessage("tempban slxtemporary 10 test reason")

            assertTrue(admins.isIDBanned(uuid), "an online player without an account should still get a vanilla ban")
            assertTrue(
                waitUntil(10000) { pluginData.data.tempBans.containsKey(uuid) },
                "the expiry of an online player without an account should be kept in the plugin data"
            )
        } finally {
            admins.unbanPlayerID(uuid)
            runBlocking { TempBan.clearBanExpire(uuid) }
            players.remove(data)
            target.remove()
            Groups.player.update()
        }
    }
}
