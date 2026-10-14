package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.serverCommand
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.data.PlayerData
import essential.common.database.data.getPlayerData
import essential.common.permission.Permission
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
}
