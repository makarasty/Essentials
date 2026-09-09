import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.pumpApp
import PluginTest.Companion.serverCommand
import PluginTest.Companion.waitUntil
import essential.common.database.data.getPlayerData
import essential.core.TempBan
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Making a temp ban permanent used to be impossible without unbanning: the expiry lives in the shared
 * player row, the sweep lifts the ban within thirty seconds of it passing, and the only command that
 * cleared it also lifted the ban. permaban clears the expiry and nothing else.
 */
class PermaBanTest {
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
    fun permaban_clearsTheExpiryAndLeavesTheBan() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val admins = Vars.netServer.admins

        serverCommand.handleMessage("tempban $uuid 10 test reason")
        assertTrue(waitUntil(10000) { admins.isIDBanned(uuid) }, "tempban should create the ban this test is about")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } != null },
            "tempban should store the expiry this test is about"
        )
        // tempban bans the id only, so an ip ban has to be placed here for the assertion below to have
        // anything to lose: unbanPlayerID drops every ip the player has, and banPlayerID never restores them.
        val ip = admins.getInfo(uuid).lastIP
        admins.banPlayerIP(ip)
        assertTrue(admins.bannedIPs.contains(ip), "the ip ban this test is about should be in place")

        serverCommand.handleMessage("permaban $uuid")

        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } == null },
            "permaban should clear the expiry"
        )
        assertTrue(admins.isIDBanned(uuid), "permaban must not lift the ban")
        assertTrue(
            admins.bannedIPs.contains(ip),
            "permaban must not disturb the player's ip bans, which an unban would drop"
        )

        // The whole point: the sweep no longer has an expiry to act on, so the ban stays.
        runBlocking { TempBan.tick() }
        pumpApp()
        assertTrue(admins.isIDBanned(uuid), "with no expiry the scheduler must leave the ban alone")

        admins.unbanPlayerID(uuid)
        admins.unbanPlayerIP(ip)
    }

    @Test
    fun permaban_doesNotLeaveALiftingTokenBehind() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val admins = Vars.netServer.admins

        serverCommand.handleMessage("tempban $uuid 10 test reason")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } != null },
            "tempban should store the expiry this test is about"
        )

        serverCommand.handleMessage("permaban $uuid")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } == null },
            "permaban should clear the expiry"
        )

        // The token is consumed by TempBan.onUnban, which runs from the PlayerUnbanEvent handler. The
        // unban command clears the expiry itself before lifting the ban, so driving this through the
        // command would pass either way: it has to go through the event.
        serverCommand.handleMessage("tempban $uuid 10 test reason")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } != null },
            "the second tempban should store its own expiry"
        )

        admins.unbanPlayerID(uuid)

        assertFalse(admins.isIDBanned(uuid), "the ban should be lifted")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.banExpireDate } == null },
            "an unban after a permaban must still be seen as a human unban and clear the expiry"
        )
    }
}
