package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.serverCommand
import PluginTest.Companion.clientCommand
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.data.PlayerData
import essential.common.database.data.getPlayerData
import essential.common.permission.Permission
import essential.common.rootPath
import essential.common.util.findPlayerData
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.gen.Player
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Privileges follow the device (usid) a server bound them to, not the uuid a client claims. */
class DeviceBindingTest {
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

    private fun rejoin(uuid: String, usid: String): Pair<Player, PlayerData> {
        val p = createPlayer()
        p.con.uuid = uuid
        p.con.usid = usid
        return p to joinPlayer(p)
    }

    /** vanilla rewrites a non-admin's adminUsid on every join, so it is never a binding. */
    @Test
    fun aStaleAdminUsidIsNotBound() {
        val admins = Vars.netServer.admins
        val (target, _) = newPlayer()
        val uuid = target.uuid()
        try {
            admins.getInfo(uuid).adminUsid = "lastJoinerDevice"
            setPermission(target, "admin", false)
            assertEquals(target.usid(), admins.getInfo(uuid).adminUsid, "the online session's device should be bound")
            assertEquals(target.usid(), Permission.boundDevice(uuid))
        } finally {
            leavePlayer(target)
        }
    }

    /** An offline promote binds nothing; the player's own next join does. */
    @Test
    fun anOfflinePromoteBindsAtTheNextJoin() {
        val admins = Vars.netServer.admins
        val (target, _) = newPlayer()
        val uuid = target.uuid()
        val usid = target.usid()
        leavePlayer(target)
        admins.getInfo(uuid).adminUsid = "lastJoinerDevice"

        serverCommand.handleMessage("setperm $uuid admin")
        assertTrue(waitUntil(10000) { runBlocking { getPlayerData(uuid)?.permission } == "admin" }, "the promote should reach the row")
        assertFalse(admins.getInfo(uuid).admin, "an offline promote must not bind anything")
        assertNull(Permission.boundDevice(uuid))

        val (back, _) = rejoin(uuid, usid)
        try {
            assertEquals(usid, Permission.boundDevice(uuid), "the own join binds the connecting device")
            assertTrue(back.admin())
        } finally {
            leavePlayer(back)
        }
    }

    /** An offline owner's row has no live connection, so it is never held back: it keeps its rank. */
    @Test
    fun anOfflineOwnerKeepsTheirGroup() {
        val (owner, _) = newPlayer()
        val uuid = owner.uuid()
        setPermission(owner, "owner", false)
        leavePlayer(owner)

        val stored = runBlocking { getPlayerData(uuid) }!!
        assertEquals("owner", Permission.effectiveGroup(stored), "a placeholder Player's usid is not a device")
        assertEquals(3, Permission.rank(Permission.effectiveGroup(stored)))
    }

    /** A moderator group (no admin flag, privileged nodes) ranks above players and is bound like an admin. */
    @Test
    fun aModeratorIsRankedAndBound() {
        val mainFile = rootPath.child("permission.yaml")
        val good = mainFile.readString()
        val (mod, _) = newPlayer()
        val (pleb, _) = newPlayer()
        val uuid = mod.uuid()
        val usid = mod.usid()
        try {
            mainFile.writeString(good.trimEnd() + "\nmoderator:\n  inheritance: user\n  permission:\n    - strict\n", false)
            Permission.load()
            assertEquals(1, Permission.rank("moderator"))
            setPermission(mod, "moderator", false)
            setPermission(pleb, "user", false)
            assertEquals(usid, Permission.boundDevice(uuid), "a moderator is bound like an admin")
            assertFalse(Vars.netServer.admins.getInfo(uuid).admin, "but is not made a vanilla admin")

            clientCommand.handleMessage("/strict ${pleb.uuid()}", mod)
            assertEquals(true, findPlayerData(pleb.uuid())?.strictMode, "a moderator still acts on a plain player")
            leavePlayer(mod)

            val (impostor, impostorData) = rejoin(uuid, "anotherDevice")
            try {
                assertEquals("moderator", impostorData.permission)
                assertFalse(Permission.check(impostorData, "strict"), "a moderator's uuid on another device is held back")
            } finally {
                leavePlayer(impostor)
            }
        } finally {
            mainFile.writeString(good, false)
            Permission.load()
            leavePlayer(pleb)
        }
    }

    /** permission_user.yaml's admin: true names a uuid; it reaches only the device this server bound. */
    @Test
    fun aFileAdminFlagFollowsTheDevice() {
        val userFile = rootPath.child("permission_user.yaml")
        val good = userFile.readString()
        val (target, _) = newPlayer()
        val uuid = target.uuid()
        val usid = target.usid()
        try {
            setPermission(target, "user", false)
            userFile.writeString(good.trimEnd() + "\n$uuid:\n  name: BoundName\n  group: user\n  admin: true\n", false)
            Permission.load()
            PluginTest.pumpApp()
            assertEquals(usid, Permission.boundDevice(uuid), "the online session is bound on reload")
            assertTrue(target.admin(), "the bound device gets the file's admin flag")
            assertEquals("BoundName", target.name(), "and the file's display name")
            leavePlayer(target)

            val (impostor, _) = rejoin(uuid, "anotherDevice")
            try {
                assertFalse(impostor.admin(), "the file's admin flag must not reach another device")
                assertNull(Permission.assignedName(uuid), "nor the file's display name")
                assertTrue(impostor.name() != "BoundName", "the impostor keeps their own name")
                assertFalse(Permission.isAdmin(uuid, "user"))
            } finally {
                leavePlayer(impostor)
            }
        } finally {
            userFile.writeString(good, false)
            Permission.load()
        }
    }
    /** What chat and achievements read from Permission[data] is the session's group, not the stored one. */
    @Test
    fun anImpostorChatsAsTheDefaultGroup() {
        val (owner, _) = newPlayer()
        val uuid = owner.uuid()
        setPermission(owner, "owner", false)
        leavePlayer(owner)

        val (impostor, impostorData) = rejoin(uuid, "anotherDevice")
        try {
            assertEquals(Permission.default, Permission[impostorData].group, "chat format follows this group")
            assertEquals(Permission.default, Permission.effectiveGroup(impostorData), "MeetOwner reads this")
            assertEquals(Permission.default, essential.core.service.chat.ChatFormatResolver.resolve("%player.permission", impostorData, ""))
        } finally {
            leavePlayer(impostor)
        }
    }

    /** A revoke in the shared column (made on another server) is not undone by this server's vanilla flag. */
    @Test
    fun aRevokeElsewhereIsNotUndoneByJoiningHere() {
        val admins = Vars.netServer.admins
        val (admin, _) = newPlayer()
        val uuid = admin.uuid()
        val usid = admin.usid()
        setPermission(admin, "admin", false)
        assertTrue(admins.getInfo(uuid).admin)
        leavePlayer(admin)

        val row = runBlocking { getPlayerData(uuid) }!!
        row.permission = "user"
        runBlocking { row.update() }

        val (back, backData) = rejoin(uuid, usid)
        try {
            assertEquals("user", backData.permission, "the join must not promote a revoked admin back")
            assertFalse(admins.getInfo(uuid).admin, "the local vanilla flag follows the shared revoke")
            assertFalse(back.admin())
        } finally {
            leavePlayer(back)
        }
    }

    /** A join on temporary data (database stall) leaves the binding and the vanilla flag alone. */
    @Test
    fun aTemporaryJoinKeepsTheBinding() {
        val admins = Vars.netServer.admins
        val (owner, _) = newPlayer()
        val uuid = owner.uuid()
        val usid = owner.usid()
        try {
            setPermission(owner, "owner", false)
            val stand = essential.common.database.data.createTemporaryPlayerData(owner)
            stand.temporary = true
            stand.permission = Permission.default
            attachPlayerData(stand, false)
            assertEquals(usid, Permission.boundDevice(uuid), "the binding survives a temporary join")
            assertTrue(admins.getInfo(uuid).admin, "and so does the vanilla flag")
        } finally {
            leavePlayer(owner)
        }
    }

    /** A group permission.yaml no longer defines is not a demotion: the binding and flag stay. */
    @Test
    fun anUnknownGroupDropsNothing() {
        val admins = Vars.netServer.admins
        val (owner, _) = newPlayer()
        val uuid = owner.uuid()
        try {
            setPermission(owner, "owner", false)
            Permission.syncVanillaAdmin(uuid, "renamedAway")
            assertEquals(owner.usid(), Permission.boundDevice(uuid))
            assertTrue(admins.getInfo(uuid).admin)
        } finally {
            leavePlayer(owner)
        }
    }

    /** Any node the plain player groups lack makes a group privileged, server-acting ones included. */
    @Test
    fun aServerActingNodeIsPrivileged() {
        val mainFile = rootPath.child("permission.yaml")
        val good = mainFile.readString()
        try {
            mainFile.writeString(good.trimEnd() + "\nwarden:\n  inheritance: user\n  permission:\n    - killall\n", false)
            Permission.load()
            assertEquals(1, Permission.rank("warden"), "killall acts on the server")
            assertEquals(2, Permission.rank("admin"), "an admin outranks a moderator")
            assertEquals(3, Permission.rank("owner"))
            assertEquals(0, Permission.rank("user"))
            assertEquals(0, Permission.rank(Permission.default))
        } finally {
            mainFile.writeString(good, false)
            Permission.load()
        }
    }

    /** A console `admin add` survives a join from another device: that session is held back, not a revoke. */
    @Test
    fun aConsoleAdminSurvivesAnotherDevice() {
        val admins = Vars.netServer.admins
        val (first, _) = newPlayer()
        val uuid = first.uuid()
        val usid = first.usid()
        leavePlayer(first)
        val row = runBlocking { getPlayerData(uuid) }!!
        row.permission = Permission.default
        runBlocking { row.update() }
        admins.adminPlayer(uuid, usid)

        val (impostor, impostorData) = rejoin(uuid, "anotherDevice")
        try {
            assertFalse(impostor.admin(), "the other device gets nothing")
            assertEquals(Permission.default, impostorData.permission, "and is not imported into the admin group")
            assertTrue(admins.getInfo(uuid).admin, "the console grant stays for the bound device")
            assertEquals(usid, admins.getInfo(uuid).adminUsid)
        } finally {
            leavePlayer(impostor)
            admins.unAdminPlayer(uuid)
        }
    }
    /** With auth on the default is "user"; permission.yaml's default group (visitor) is still plain. */
    @Test
    fun theFileDefaultGroupIsPlainWithAuthOn() {
        val before = Permission.default
        try {
            Permission.setAuthDefault("user")
            assertEquals(0, Permission.rank("visitor"))
            assertEquals(0, Permission.rank("user"))
        } finally {
            Permission.setAuthDefault(before)
        }
    }
    /** Demoting an online admin by editing permission_user.yaml and reloading drops the binding and flag. */
    @Test
    fun aFileDemotionDropsTheBinding() {
        val admins = Vars.netServer.admins
        val userFile = rootPath.child("permission_user.yaml")
        val good = userFile.readString()
        val (target, _) = newPlayer()
        val uuid = target.uuid()
        try {
            userFile.writeString(good.trimEnd() + "\n$uuid:\n  group: admin\n", false)
            Permission.load()
            PluginTest.pumpApp()
            assertEquals(target.usid(), Permission.boundDevice(uuid), "the file promotion binds the session")
            assertTrue(admins.getInfo(uuid).admin)

            userFile.writeString(good.trimEnd() + "\n$uuid:\n  group: user\n", false)
            Permission.load()
            PluginTest.pumpApp()
            assertNull(Permission.boundDevice(uuid), "the file demotion drops the binding")
            assertFalse(admins.getInfo(uuid).admin, "and the vanilla flag")
            assertFalse(target.admin())
        } finally {
            userFile.writeString(good, false)
            Permission.load()
            leavePlayer(target)
        }
    }
}
