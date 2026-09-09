package essential.core.service.protect

import PluginTest.Companion.clientCommand
import arc.Events
import arc.util.Log
import essential.common.database.data.createPlayerData
import essential.common.database.data.getPlayerData
import essential.common.util.findPlayerData
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Rules
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the boot path of the protect module and the two commands that can move an account between
 * devices. The rest of the module's rules are covered by [ProtectRulesTest].
 */
class ProtectBootTest {
    companion object {
        private var loaded = false
        private var counter = 0
    }

    private lateinit var originalVpnList: Array<String>
    private lateinit var originalProtect: ProtectConfig
    private lateinit var originalRules: Rules

    @BeforeTest
    fun setup() {
        if (!loaded) {
            PluginTest.loadGame(true)
            loaded = true
        }
        originalVpnList = ProtectService.pluginData.vpnList
        originalProtect = ProtectService.conf
        originalRules = Vars.state.rules
        ProtectService.conf = ProtectConfig()
    }

    @AfterTest
    fun cleanup() {
        ProtectService.pluginData.vpnList = originalVpnList
        ProtectService.conf = originalProtect
        Vars.state.rules = originalRules
        pvpCount = 0
    }

    @Test
    fun peace_mode_is_applied_to_the_rules_the_round_actually_runs_with() {
        ProtectService.conf.pvp.peace.enabled = true
        ProtectService.conf.pvp.peace.time = 60

        // Every map path replaces state.rules from applyRules() after the world load has fired and
        // before logic.play(), so peace applied only at world load lands on a discarded object.
        Vars.state.rules = Rules().also { it.pvp = true }
        Events.fire(EventType.PlayEvent())

        assertEquals(0f, Vars.state.rules.blockDamageMultiplier, "peace did not zero block damage on the round's own rules")
        assertEquals(0f, Vars.state.rules.unitDamageMultiplier, "peace did not zero unit damage on the round's own rules")
        assertEquals(60, pvpCount, "the peace countdown was not started")
    }

    @Test
    fun a_vpn_list_that_cannot_be_fetched_does_not_escape_the_boot_path() {
        // Port 9 (discard) is closed on the loopback here, so this fails the way an unreachable
        // GitHub does: before the fix the exception left every handler in the module unregistered.
        // The harness turns any logged error into a test failure, and this path logs one on purpose.
        val previousLogger = Log.logger
        Log.logger = Log.LogHandler { _, _ -> }
        val list = try {
            downloadVpnList("http://127.0.0.1:9/ipv4.txt")
        } finally {
            Log.logger = previousLogger
        }

        assertNull(list, "an unreachable list came back as a list instead of null")
    }

    @Test
    fun the_vpn_list_is_parsed_into_matchers_once_when_it_is_set() {
        ProtectService.pluginData.vpnList = arrayOf("10.8.0.0/16", "<!DOCTYPE html>", "192.0.2.7")

        val matchers = ProtectService.pluginData.vpnMatchers
        assertEquals(2, matchers.size, "a line that does not parse was kept as a matcher")
        assertTrue(
            matchers.any { it.matches(InetAddress.getByName("10.8.4.9")) },
            "an address inside a listed range was not matched"
        )
        assertFalse(
            matchers.any { it.matches(InetAddress.getByName("10.9.4.9")) },
            "an address outside every listed range was matched"
        )
    }

    @Test
    fun the_login_delete_confirmation_survives_the_second_attempt() {
        val (device, accountId, password) = deviceWithAnotherAccount()

        clientCommand.handleMessage("/login $accountId $password", device)
        assertFalse(
            PluginTest.waitUntil(4000) { accountMoved(device.uuid(), accountId) },
            "the account was taken over on the first attempt, without the delete confirmation"
        )

        clientCommand.handleMessage("/login $accountId $password", device)
        assertTrue(
            PluginTest.waitUntil(15000) { accountMoved(device.uuid(), accountId) },
            "repeating /login never confirmed the delete: the confirmation did not survive the first call"
        )
    }

    @Test
    fun a_wrong_password_never_moves_the_account() {
        val (device, accountId, _) = deviceWithAnotherAccount()

        // Twice, because a single attempt is stopped by the delete confirmation whatever the
        // password is - one call cannot tell a rejected password from an unconfirmed delete.
        clientCommand.handleMessage("/login $accountId not-the-password", device)
        assertFalse(
            PluginTest.waitUntil(4000) { accountMoved(device.uuid(), accountId) },
            "an account moved to a device that gave the wrong password"
        )

        clientCommand.handleMessage("/login $accountId not-the-password", device)
        assertFalse(
            PluginTest.waitUntil(4000) { accountMoved(device.uuid(), accountId) },
            "repeating a wrong password confirmed the delete and moved the account"
        )
    }

    /**
     * A player who is online with a row of their own but no loaded [essential.common.database.data.PlayerData],
     * which is what `feature.playerData.allowWithoutData = false` produces, plus a second account for
     * them to log in to. Logging in across those two is the device-conflict path.
     */
    private fun deviceWithAnotherAccount(): Triple<mindustry.gen.Player, String, String> {
        // The accountID column holds 25 characters, so these stay short.
        val serial = counter++
        val device = PluginTest.createPlayer()
        val stamp = System.currentTimeMillis() % 1000000
        val accountId = "pb-$serial-$stamp"
        val password = "correct-horse-$serial"

        runBlocking {
            createPlayerData(device.name(), device.uuid(), "own-$accountId", password)
            createPlayerData("holder-$serial-$stamp", "holder-$accountId", accountId, password)
        }
        assertNull(findPlayerData(device.uuid()), "the device player was published after all")

        return Triple(device, accountId, password)
    }

    private fun accountMoved(uuid: String, accountId: String): Boolean =
        runBlocking { getPlayerData(uuid) }?.accountID == accountId
}
