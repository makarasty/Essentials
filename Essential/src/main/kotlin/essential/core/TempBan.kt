package essential.core

import arc.Core
import arc.util.Log
import arc.util.Timer
import essential.common.database.data.getPlayerData
import essential.common.database.data.update
import essential.common.database.table.PlayerTable
import essential.common.pluginData
import essential.common.systemTimezone
import essential.common.util.findPlayerData
import essential.core.Main.Companion.scope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import mindustry.Vars
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
object TempBan {
    private const val INTERVAL = 30f

    /**
     * Bans the scheduler still intends to lift. Withdrawing a uuid cancels the posted unban, which is
     * how a permaban landing between the sweep and its post survives; the unban that does go through
     * must not wipe the expiry.
     *
     * The token is removed by [onUnban], never by the post that triggers it: `PlayerUnbanEvent` is
     * fired inside `unbanPlayerID`, but its handler launches a coroutine, so a guard that consumed the
     * token here would drain it before [onUnban] ever read it and the expiry would be wiped.
     */
    private val lifting = ConcurrentHashMap.newKeySet<String>()

    /**
     * Held while a batch is chosen and while a ban is declared non-expiring. Without it the two
     * overlap: the sweep reads a row whose expiry is still set, a permaban clears it and finds no
     * token to withdraw, and the sweep then adds one and lifts the ban that was just made permanent.
     */
    private val sweep = Mutex()

    fun start() {
        // The ban list is game state, so it is read on the main thread and handed to the sweep.
        Timer.schedule({ Core.app.post { scheduleTick() } }, INTERVAL, INTERVAL)
    }

    /**
     * The Core.app.post body [start] schedules, pulled out so a test can call it directly instead
     * of waiting on a 30-second Timer. [readBanned] is [localBans] in production and a
     * thread-recording probe in TempBanSweepThreadTest.
     *
     * [readBanned] has to be called here, synchronously, and only its result may cross into the
     * `scope.launch` below. `scope.launch { tick(readBanned()) }` reads as "the read happens
     * inside the block Core.app.post already put on the main thread" but does not do that:
     * scope.launch dispatches its whole block onto Dispatchers.IO before evaluating any of it,
     * argument expressions included, so the read would run on an IO thread despite this function
     * itself running on whatever thread Core.app.post delivered to.
     */
    internal fun scheduleTick(readBanned: () -> Set<String> = ::localBans) {
        val banned = readBanned()
        scope.launch { tick(banned) }
    }

    private fun localBans(): Set<String> =
        Vars.netServer?.admins?.banned?.toList()?.map { it.id }?.toSet() ?: emptySet()

    /** For tests, which already run on the application thread. */
    suspend fun tick() = tick(localBans())

    suspend fun tick(banned: Set<String>) {
        if (banned.isEmpty()) return
        val now = Clock.System.now().toLocalDateTime(systemTimezone)

        // Only the server that issued a temp ban holds it in Mindustry's own list, so the sweep
        // lifts what is expired in the database AND banned here. The expiry column is left
        // alone: a timestamp in the past already says "over", and clearing it would hide the
        // row from a sibling server that holds its own ban on the same player.
        val (expired, orphanedExpired) = sweep.withLock {
            val stored = runCatching {
                suspendTransaction {
                    PlayerTable.select(PlayerTable.uuid)
                        .where { PlayerTable.banExpireDate lessEq now }
                        .map { it[PlayerTable.uuid] }
                        .toList()
                }
            }.onFailure { Log.err("Failed to read temp ban expiries from the database", it) }.getOrDefault(emptyList())
            val orphanedExpired = orphaned().filterValues { it <= now }.keys

            val expired = (stored + orphanedExpired).filter { it in banned }.toSet()
            lifting += expired
            expired to orphanedExpired
        }
        if (expired.isEmpty()) return
        // Lifting a ban changes game state and fires PlayerUnbanEvent; that belongs on the main thread.
        // A permaban can land between this batch being chosen and the post running, and it withdraws
        // the token; unbanning anyway would lift the ban that was just made permanent. An unban that
        // reports nothing to do fires no event, so its token is dropped here or it would strand and
        // make the next genuine unban look like the scheduler's.
        Core.app.post {
            expired.forEach { if (it in lifting && !Vars.netServer.admins.unbanPlayerID(it)) lifting -= it }
        }
        if (orphanedExpired.any { it in expired }) {
            expired.forEach { pluginData.data.tempBans.remove(it) }
            pluginData.update()
        }
    }

    /** Called from the PlayerUnbanEvent handler: a human unban forgets the expiry, the scheduler's does not. */
    suspend fun onUnban(uuid: String) {
        if (lifting.remove(uuid)) return
        clearBanExpire(uuid)
    }

    suspend fun setBanExpire(uuid: String, expire: LocalDateTime) {
        val stored = runCatching {
            val data = findPlayerData(uuid)?.takeIf { !it.temporary } ?: getPlayerData(uuid)
            if (data != null) {
                data.banExpireDate = expire
                data.update()
            } else {
                false
            }
        }.onFailure { Log.err("Failed to store the temp ban expiry of $uuid in the database, keeping it in plugin data", it) }
            .getOrDefault(false)
        if (stored) {
            // A write can succeed after an earlier call for the same uuid fell back to plugin
            // data - a database outage, then a later setperm or extend once it recovers. Leaving
            // that fallback entry in place gives the sweep two disagreeing expiries for the same
            // player, and orphaned() has no way to tell which one is current, so the shorter of
            // the two always wins and can lift a ban early.
            if (pluginData.data.tempBans.remove(uuid) != null) pluginData.update()
            return
        }
        pluginData.data.tempBans[uuid] = expire.toString()
        pluginData.update()
    }

    suspend fun clearBanExpire(uuid: String) = sweep.withLock {
        // Whoever calls this has decided the ban is not expiring, so withdraw it from a sweep that is
        // already in flight. onUnban has consumed the token by the time it reaches here, so this is a
        // no-op on that path.
        lifting -= uuid
        findPlayerData(uuid)?.banExpireDate = null
        runCatching {
            suspendTransaction {
                PlayerTable.update({ PlayerTable.uuid eq uuid }) { it[banExpireDate] = null }
            }
        }.onFailure { Log.err("Failed to clear the temp ban expiry of $uuid in the database", it) }
        if (pluginData.data.tempBans.remove(uuid) != null) pluginData.update()
    }

    private fun orphaned(): Map<String, LocalDateTime> =
        pluginData.data.tempBans.mapNotNull { (uuid, date) ->
            runCatching { uuid to LocalDateTime.parse(date) }.getOrNull()
        }.toMap()
}
