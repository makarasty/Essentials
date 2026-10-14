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
        assertEquals(2, Permission.rank(Permission.effectiveGroup(stored)))
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
            userFile.writeString(good.trimEnd() + "\n$uuid:\n  group: user\n  admin: true\n", false)
            Permission.load()
            PluginTest.pumpApp()
            assertEquals(usid, Permission.boundDevice(uuid), "the online session is bound on reload")
            assertTrue(target.admin(), "the bound device gets the file's admin flag")
            leavePlayer(target)

            val (impostor, _) = rejoin(uuid, "anotherDevice")
            try {
                assertFalse(impostor.admin(), "the file's admin flag must not reach another device")
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
}
