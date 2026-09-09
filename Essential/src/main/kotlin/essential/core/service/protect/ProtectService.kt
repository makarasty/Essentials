package essential.core.service.protect

import arc.util.CommandHandler
import arc.util.Log
import arc.util.Timer
import essential.common.bundle.Bundle
import essential.common.config.Config
import essential.common.database.data.PlayerData
import essential.common.permission.Permission
import essential.common.players
import essential.common.util.findPlayerData
import essential.core.Main
import essential.core.service.protect.ProtectConfig.AuthType
import essential.core.service.protect.generated.registerGeneratedClientCommands
import essential.core.service.protect.generated.registerGeneratedEventHandlers
import kotlinx.coroutines.runBlocking
import mindustry.Vars.netServer
import mindustry.gen.Groups
import mindustry.mod.Plugin
import java.net.URI
import java.util.Objects.requireNonNull


class ProtectService : Plugin() {
    companion object {
        var bundle: Bundle = Bundle()
        var conf: ProtectConfig = reloadConf()

        fun reloadConf() : ProtectConfig {
            return runBlocking {
                val config = Config.load("config_protect", ProtectConfig.serializer(), ProtectConfig())
                require(config != null) {
                    Log.err(bundle["event.plugin.load.failed"])
                }
                config
            }
        }

        fun isPasswordAuthenticationEnabled(): Boolean =
            conf.account.enabled && conf.account.getAuthType() == ProtectConfig.AuthType.Password

        var pluginData: PluginData = PluginData()
    }

    override fun init() {
        bundle.prefix = "[EssentialProtect]"

        netServer.admins.addActionFilter { action ->
            if (action.player == null) return@addActionFilter true
            val data: PlayerData? = findPlayerData(action.player.uuid())
            if (data != null) {
                // 계정 기능이 켜져있는 경우
                if (conf.account.enabled) {
                    // Discord 인증을 사용할 경우
                    if (requireNonNull(conf.account.getAuthType()) == ProtectConfig.AuthType.Discord) {
                        // 계정에 Discord 인증이 안되어 있는 경우
                        if (data.discordID == null) {
                            action.player.sendMessage(Bundle(action.player.locale)["event.discord.not.registered"])
                            return@addActionFilter false
                        }
                    } else {
                        return@addActionFilter true
                    }
                }
                return@addActionFilter true
            } else if (conf.account.enabled) {
                return@addActionFilter false
            } else {
                return@addActionFilter Main.conf.feature.playerData.allowWithoutData
            }
        }

        // 계정 설정 유무에 따라 기본 권한 변경
        if (conf.account.getAuthType() != ProtectConfig.AuthType.None) {
            Permission.setAuthDefault("user")
        } else {
            Permission.setAuthDefault("visitor")
        }

        if (conf.rules.blockNewUser) {
            enableBlockNewUser()
        }

        if (conf.account.getAuthType() == AuthType.Password) {
            Timer.schedule({
                Groups.player.forEach {
                    if (players.none { e -> e.uuid == it.uuid() }) {
                        it.sendMessage(Bundle(it.locale)["event.player.first.register"])
                    }
                }
            }, 0f, 20f)
        }

        // 이벤트 설정
        registerGeneratedEventHandlers()

        // VPN 확인 - the registration above must already be done: an unreachable list used
        // to take every handler in this module down with it.
        if (conf.rules.vpn) {
            downloadVpnList()?.let { pluginData.vpnList = it }
        }
    }


    override fun registerClientCommands(handler: CommandHandler) {
        registerGeneratedClientCommands(handler)
        if (conf.account.getAuthType() != ProtectConfig.AuthType.Password || !conf.account.enabled) {
            handler.removeCommand("reg")
        }
    }
}

internal const val VPN_LIST_URL = "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv4.txt"

/**
 * The JVM default timeouts are unbounded, and this used to run ahead of handler registration, so an
 * unreachable list took the whole module down with it. A failure leaves the rule inert rather than
 * refusing everyone, the way [enableBlockNewUser] stands its rule down.
 */
internal fun downloadVpnList(url: String = VPN_LIST_URL): Array<String>? {
    return try {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        val list = connection.getInputStream().use { it.reader().readText() }
        list.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    } catch (e: Exception) {
        Log.err("Failed to download the VPN address list, the vpn rule stays inert", e)
        null
    }
}
