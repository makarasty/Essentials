package essential.core.service.protect

import arc.Events
import essential.common.database.data.createBanInfo
import essential.core.CoreConfig
import essential.core.Main
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.game.EventType
import mindustry.net.NetConnection
import mindustry.net.Packets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectRulesTest {
    companion object {
        private var loaded = false
    }

    private lateinit var originalCore: CoreConfig
    private lateinit var originalProtect: ProtectConfig

    @BeforeTest
    fun setup() {
        if (!loaded) {
            PluginTest.loadGame(true)
            loaded = true
        }
        originalCore = Main.conf
        originalProtect = ProtectService.conf
        ProtectService.conf = ProtectConfig()
        coldData = null
    }

    @AfterTest
    fun cleanup() {
        Main.conf = originalCore
        ProtectService.conf = originalProtect
        coldData = null
    }

    private fun connection(address: String = "192.168.0.5") = object : NetConnection(address) {
        override fun send(`object`: Any?, reliable: Boolean) = Unit
        override fun close() = Unit
    }

    private fun connect(con: NetConnection, uuid: String, name: String = "tester", mobile: Boolean = false) {
        val packet = Packets.ConnectPacket()
        packet.name = name
        packet.uuid = uuid
        packet.usid = uuid
        packet.locale = "en"
        packet.mobile = mobile
        con.uuid = uuid
        Events.fire(EventType.ConnectPacketEvent(con, packet))
    }

    private fun useDatabaseBans(enabled: Boolean) {
        Main.conf = Main.conf.copy(ban = Main.conf.ban.copy(useDatabase = enabled))
    }

    @Test
    fun the_cold_uuid_list_holds_every_known_player() {
        val (player, _) = PluginTest.newPlayer()

        enableBlockNewUser()

        assertTrue(PluginTest.waitUntil(15000) { coldData != null }, "the uuid list never loaded")
        assertTrue(coldData!!.contains(player.uuid()), "a player with a row was missing from the list")
    }

    @Test
    fun block_new_user_lets_a_returning_player_in() {
        val (player, _) = PluginTest.newPlayer()
        useDatabaseBans(false)
        ProtectService.conf.rules.blockNewUser = true

        enableBlockNewUser()
        assertTrue(PluginTest.waitUntil(15000) { coldData != null }, "the uuid list never loaded")

        val returning = connection()
        connect(returning, player.uuid())
        PluginTest.pumpApp()
        assertFalse(returning.kicked, "a player who has a row in the database was kicked as new")

        val stranger = connection()
        connect(stranger, "uuid-that-has-no-row")
        PluginTest.pumpApp()
        assertTrue(stranger.kicked, "a uuid with no row was not blocked")
    }

    @Test
    fun the_mobile_rule_blocks_mobile_players_only_when_it_is_switched_on() {
        val (player, _) = PluginTest.newPlayer()
        useDatabaseBans(false)

        ProtectService.conf.rules.mobile = false
        val allowed = connection()
        connect(allowed, player.uuid(), mobile = true)
        PluginTest.pumpApp()
        assertFalse(allowed.kicked, "a mobile player was refused while the rule was off")

        ProtectService.conf.rules.mobile = true
        val refused = connection()
        connect(refused, player.uuid(), mobile = true)
        PluginTest.pumpApp()
        assertTrue(refused.kicked, "a mobile player joined while the rule was on")

        val desktop = connection()
        connect(desktop, player.uuid(), mobile = false)
        PluginTest.pumpApp()
        assertFalse(desktop.kicked, "a desktop player was caught by the mobile rule")
    }

    @Test
    fun the_vpn_rule_does_not_swallow_the_database_ban_check() {
        val (player, _) = PluginTest.newPlayer()
        runBlocking { createBanInfo(Vars.netServer.admins.getInfo(player.uuid()), "test ban") }

        useDatabaseBans(true)
        ProtectService.conf.rules.vpn = true
        ProtectService.conf.rules.blockNewUser = false

        val con = connection()
        connect(con, player.uuid(), player.plainName())

        assertTrue(
            PluginTest.waitUntil(15000) { con.kicked },
            "a banned player joined because the vpn rule consumed the rule chain"
        )
    }

    @Test
    fun a_vpn_list_line_that_does_not_parse_does_not_skip_the_ban_check() {
        val (player, _) = PluginTest.newPlayer()
        runBlocking { createBanInfo(Vars.netServer.admins.getInfo(player.uuid()), "test ban") }

        useDatabaseBans(true)
        ProtectService.conf.rules.vpn = true
        ProtectService.conf.rules.blockNewUser = false
        val originalList = ProtectService.pluginData.vpnList
        ProtectService.pluginData.vpnList = arrayOf("<!DOCTYPE html>")

        try {
            val con = connection()
            connect(con, player.uuid(), player.plainName())

            assertTrue(
                PluginTest.waitUntil(15000) { con.kicked },
                "a banned player joined because one unparseable vpn line threw out of the handler"
            )
        } finally {
            ProtectService.pluginData.vpnList = originalList
        }
    }
}
