package essential.core.service.protect

import arc.Events
import arc.net.Server
import arc.net.ServerDiscoveryHandler
import arc.util.Log
import arc.util.Timer
import essential.common.bundle.Bundle
import essential.common.database.data.*
import essential.common.database.table.PlayerTable
import essential.common.event.CustomEvents
import essential.common.log.LogType
import essential.common.log.writeLog
import essential.common.players
import essential.core.Main.Companion.scope
import essential.core.Main.Companion.conf as coreConf
import essential.core.ServerDescription
import essential.core.firePlayerDataLoad
import essential.core.loadJoinedPlayerData
import essential.core.useTemporaryPlayerData
import essential.core.service.protect.ProtectService.Companion.conf
import essential.core.service.protect.ProtectService.Companion.pluginData
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import ksp.event.Event
import mindustry.Vars
import mindustry.content.Fx
import mindustry.entities.Damage
import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.net.ArcNetProvider
import mindustry.net.NetworkIO
import mindustry.net.Packets
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer

var pvpCount: Int = 0
var originalBlockMultiplier: Float = 0f
var originalUnitMultiplier: Float = 0f
/** Every uuid known to the database when new user blocking was switched on, or null while that list is unknown. */
@Volatile
var coldData: Set<String>? = null

@Event
fun worldLoadEnd(event: EventType.WorldLoadEndEvent) {
    try {
        val inner: Class<*> = (Vars.platform.net as ArcNetProvider).javaClass
        val field = inner.getDeclaredField("server")
        field.setAccessible(true)

        val serverInstance = field[Vars.platform.net]

        val innerClass: Class<*> = field[Vars.platform.net].javaClass
        val method = innerClass.getMethod("setDiscoveryHandler", ServerDiscoveryHandler::class.java)

        val handler = ServerDiscoveryHandler { inetAddress, responseHandler ->
            if (!Vars.netServer.admins.isIPBanned(inetAddress.hostAddress)) {
                val buffer: ByteBuffer = NetworkIO.writeServerData()
                buffer.position(0)
                responseHandler.respond(buffer)
            } else {
                responseHandler.respond(ByteBuffer.allocate(0))
            }
        }

        method.invoke(serverInstance, handler)
    } catch (e: java.lang.Exception) {
        e.printStackTrace()
    }

    val filter: Server.ServerConnectFilter =
        Server.ServerConnectFilter { s -> !Vars.netServer.admins.bannedIPs.contains(s) }
    Vars.platform.net.connectFilter = filter

    applyPeaceMode()
}

/**
 * Peace has to be applied against the rules object the round will actually run with. Every map path
 * reassigns `state.rules` from `applyRules` AFTER the world load fires, so applying it only on
 * [worldLoadEnd] wrote the multipliers onto the object that was about to be thrown away and peace was
 * silently inert on every rotation, vote and host. The save-load path never calls `logic.play()`, so
 * both hooks are needed; each one recomputes from whatever `state.rules` is current, so applying
 * twice is not applying twice to the same object.
 */
@Event
fun play(e: EventType.PlayEvent) {
    applyPeaceMode()
}

private fun applyPeaceMode() {
    if (conf.pvp.peace.enabled && Vars.state.rules.pvp) {
        // A save loaded during peace time can already have the multiplier at 0; treat that as unset.
        originalBlockMultiplier = if (Vars.state.rules.blockDamageMultiplier == 0f) 1f else Vars.state.rules.blockDamageMultiplier
        originalUnitMultiplier = if (Vars.state.rules.unitDamageMultiplier == 0f) 1f else Vars.state.rules.unitDamageMultiplier
        Vars.state.rules.blockDamageMultiplier = 0f
        Vars.state.rules.unitDamageMultiplier = 0f
        pvpCount = conf.pvp.peace.time
    } else {
        pvpCount = 0
    }
    ServerDescription.placeholders["peace"] = { if (pvpCount > 0) "${pvpCount / 60}:${"%02d".format(pvpCount % 60)}" else "" }
}

@Event
fun runEverySecond() {
    Timer.schedule({
        if (conf.pvp.peace.enabled && Vars.state.rules.pvp && Vars.state.isPlaying && pvpCount > 0) {
            pvpCount--
            if (pvpCount == 0) {
                // Only restore what is still zeroed. The cached pair is what the rules held before
                // peace started, and peace lasts minutes: anything that set a multiplier in the
                // meantime - another plugin, a rules edit - owns it now, and writing the cache back
                // over it would revert a change nobody asked to revert.
                if (Vars.state.rules.blockDamageMultiplier == 0f) {
                    Vars.state.rules.blockDamageMultiplier = originalBlockMultiplier
                }
                if (Vars.state.rules.unitDamageMultiplier == 0f) {
                    Vars.state.rules.unitDamageMultiplier = originalUnitMultiplier
                }
                // The rules reach a client once, with the world snapshot. Without this the client
                // keeps predicting the peace multipliers and shows damage the server discards.
                Call.setRules(Vars.state.rules)
                players.forEach {
                    it.send("event.pvp.peace.end")
                }
            }
            ServerDescription.changed()
        }
    }, 0f, 1f)
}

@Event
fun update() {
    if (conf.pvp.border.enabled) {
        val maxX = Vars.world.width() * 8
        val maxY = Vars.world.height() * 8
        Groups.unit.forEach { unit ->
            if (unit.x < 0 || unit.y < 0 || unit.x > maxX || unit.y > maxY) {
                unit.kill()
            }
        }
    }
    if (conf.protect.unbreakableCore) {
        Vars.state.teams.active.forEach { t -> t.cores.forEach { c -> c.health(1.0E8f) } }
    }
}

@Event
fun playerJoin(e: EventType.PlayerJoin) {
    // Registered at boot and never removed, so after a config reload switches the module off this
    // still ran next to core's fallback join handler: two loads per join, and the second saw the row
    // the first had just created under the player's own name and kicked them as a duplicate.
    if (!coreConf.module.protect) return
    // The vanilla admin flag stays as it is; the group sync on data load adjusts it.
    val player = e.player
    val uuid = player.uuid()
    val plainName = player.plainName()
    val locale = player.locale
    val con = player.con

    scope.launch {
        if (conf.account.getAuthType() == ProtectConfig.AuthType.None || !conf.account.enabled) {
            val result = loadJoinedPlayerData(player, plainName)
            when {
                result.data != null -> {
                    result.data.player = player
                    firePlayerDataLoad(result.data)
                }

                else -> useTemporaryPlayerData(player, plainName)
            }
            return@launch
        }

        val data: PlayerData? = try {
            getPlayerData(uuid)
        } catch (e: Exception) {
            Log.err("Failed to load player data for $plainName ($uuid)", e)
            null
        }
        if (data != null) {
            data.player = player
        }
        if (conf.account.getAuthType() == ProtectConfig.AuthType.Discord) {
            if (data == null) {
                val exists = suspendTransaction {
                    !PlayerTable
                        .select(PlayerTable.name)
                        .where { PlayerTable.name eq plainName }
                        .empty()
                }

                if (!exists) {
                    // There is no Discord login on this path - the action filter is the only gate,
                    // and it denies silently. Say so rather than leaving the player in a server
                    // where nothing they do works.
                    arc.Core.app.post {
                        Groups.player.find { p -> p.uuid() == uuid }
                            ?.sendMessage(Bundle(locale)["event.discord.not.registered"])
                    }
                } else {
                    val reason = Bundle(locale)["event.player.name.duplicate"]
                    arc.Core.app.post {
                        con.kick(reason, 0L)
                    }
                }
            } else {
                arc.Core.app.post {
                    val activePlayer = Groups.player.find { p -> p.uuid() == uuid }
                    if (activePlayer != null) {
                        Events.fire(CustomEvents.PlayerDataLoad(data))
                    }
                }
            }
        } else {
            if (data != null) {
                arc.Core.app.post {
                    val activePlayer = Groups.player.find { p -> p.uuid() == uuid }
                    if (activePlayer != null) {
                        Events.fire(CustomEvents.PlayerDataLoad(data))
                    }
                }
            } else {
                arc.Core.app.post {
                    val activePlayer = Groups.player.find { p -> p.uuid() == uuid }
                    if (activePlayer != null) {
                        activePlayer.sendMessage(Bundle(locale)["event.player.first.register"])
                    }
                }
            }
        }
    }
}

@Event
fun blockDestroy(e: EventType.BlockDestroyEvent) {
    if (Vars.state.rules.pvp && conf.pvp.destroyCore && Vars.state.rules.coreCapture) {
        Fx.spawnShockwave.at(e.tile.getX(), e.tile.getY(), Vars.state.rules.dropZoneRadius)
        Damage.damage(
            Vars.world.tile(e.tile.pos()).team(),
            e.tile.getX(),
            e.tile.getY(),
            Vars.state.rules.dropZoneRadius,
            1.0E8f,
            true
        )
    }
}

@Event
fun playerDataLoaded(e: CustomEvents.PlayerDataLoadEnd) {
    if (conf.rules.strict) {
        Groups.player.find { p -> p.uuid() == e.playerData.uuid }?.name(e.playerData.name)
    }
}

@Event
fun connectPacket(event: EventType.ConnectPacketEvent) {
    // Shared by the async ban check and the synchronous rule checks below - both need to log and
    // announce the same way once a reason is known.
    fun kicked(reasonKey: String) {
        val bundle = Bundle()
        val reason = bundle["event.player.kick", event.packet.name, event.packet.uuid, event.connection.address, bundle["event.player.kick.reason.$reasonKey"]]
        writeLog(LogType.Player, reason)
        Log.info(reason)
        Events.fire(
            CustomEvents.PlayerConnectKicked(
                event.packet.name,
                bundle["event.player.kick.reason.$reasonKey"]
            )
        )
    }

    // Each rule guards on kickReason rather than chaining on else-if: chained on the configuration
    // flags, an enabled rule that did not reject swallowed every rule below it.
    var kickReason = ""
    // The packet, not the connection: the engine copies mobile onto the connection only after this
    // event has fired, so the connection's own flag is always false here.
    if (conf.rules.mobile && event.packet.mobile) {
        event.connection.kick(Bundle(event.packet.locale)["event.player.not.allow.mobile"], 0L)
        kickReason = "mobile"
    }
    if (kickReason.isEmpty() && conf.rules.minimalName.enabled && conf.rules.minimalName.length > event.packet.name.length) {
        event.connection.kick(Bundle(event.packet.locale)["event.player.name.short"], 0L)
        kickReason = "name.short"
    }
    if (kickReason.isEmpty() && conf.rules.vpn) {
        // The matchers are built once when the list is set and the remote address is parsed once per
        // connection, not once per entry: this runs on the main thread and the list is tens of
        // thousands of lines long. An address that will not parse matches nothing rather than
        // throwing, which would skip every rule below.
        val remote = runCatching { InetAddress.getByName(event.connection.address) }.getOrNull()
        if (remote != null && pluginData.vpnMatchers.any { it.matches(remote) }) {
            event.connection.kick(Bundle(event.packet.locale)["anti-grief.vpn"])
            kickReason = "vpn"
        }
    }
    if (kickReason.isEmpty() && conf.rules.blockNewUser && coldData?.contains(event.packet.uuid) == false) {
        event.connection.kick(Bundle(event.packet.locale)["event.player.new.blocked"], 0L)
        kickReason = "newuser"
    }
    if (kickReason.isEmpty() && coreConf.ban.useDatabase) {
        scope.launch {
            try {
                if (checkPlayerBanned(event.packet.uuid, event.connection.address, event.packet.name)) {
                    kicked("banned")
                    arc.Core.app.post {
                        event.connection.kick(Packets.KickReason.banned)
                    }
                }
            } catch (e: Exception) {
                Log.err("Failed to check if player is banned", e)
            }
        }
    }

    if (!kickReason.isEmpty()) {
        kicked(kickReason)
    }
}

fun enableBlockNewUser() {
    scope.launch {
        try {
            coldData = suspendTransaction {
                PlayerTable.select(PlayerTable.uuid).toList().mapTo(HashSet()) { it[PlayerTable.uuid] }
            }
        } catch (e: Exception) {
            // Without the list there is no way to tell a returning player from a new one, so the rule
            // stands down rather than kicking everyone who connects. Any list already loaded is kept.
            Log.err("Failed to load player UUIDs for new user blocking", e)
        }
    }
}

class IpAddressMatcher(ipAddress: String) {
    private var nMaskBits = 0
    private val requiredAddress: InetAddress

    init {
        var address = ipAddress
        if (address.indexOf('/') > 0) {
            val addressAndMask = address.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            address = addressAndMask[0]
            nMaskBits = addressAndMask[1].toInt()
        } else {
            nMaskBits = -1
        }
        requiredAddress = parseAddress(address)
        require(requiredAddress.address.size * 8 >= nMaskBits) {
            String.format(
                "IP address %s is too short for bitmask of length %d",
                address,
                nMaskBits
            )
        }
    }

    fun matches(remoteAddress: InetAddress): Boolean {
        if (requiredAddress.javaClass != remoteAddress.javaClass) {
            return false
        }
        if (nMaskBits < 0) {
            return remoteAddress == requiredAddress
        }
        val remAddr = remoteAddress.address
        val reqAddr = requiredAddress.address
        val nMaskFullBytes = nMaskBits / 8
        val finalByte = (0xFF00 shr (nMaskBits and 0x07)).toByte()
        for (i in 0..<nMaskFullBytes) {
            if (remAddr[i] != reqAddr[i]) {
                return false
            }
        }
        return if (finalByte.toInt() != 0) {
            (remAddr[nMaskFullBytes].toInt() and finalByte.toInt()) == (reqAddr[nMaskFullBytes].toInt() and finalByte.toInt())
        } else {
            true
        }
    }

    private fun parseAddress(address: String?): InetAddress {
        try {
            return InetAddress.getByName(address)
        } catch (e: UnknownHostException) {
            throw IllegalArgumentException("Failed to parse address $address", e)
        }
    }
}
