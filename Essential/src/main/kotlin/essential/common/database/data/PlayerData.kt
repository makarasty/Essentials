package essential.common.database.data

import arc.util.Log
import essential.common.bundle.Bundle
import essential.common.database.data.update as updateRow
import essential.common.database.table.AchievementTable
import essential.common.database.table.ContributionTable
import essential.common.database.table.PlayerTable
import essential.common.playerNumber
import essential.common.systemTimezone
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import ksp.table.GenerateCode
import mindustry.gen.Player
import mindustry.gen.Playerc
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.mindrot.jbcrypt.BCrypt
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal val statusJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Keys under this prefix are achievement progress, and are the only part of `status` that is saved.
 *
 * The prefix is load-bearing: a counter named anything else is session state, and will not survive a
 * restart, a reconnect, or a move to another server on the same database.
 */
internal const val RECORD_PREFIX = "record."

/**
 * The `record.*` keys that are not running totals, and so must never be added together when two
 * accounts, or a temporary data object and a real one, are merged.
 *
 * Summing a window turns two half-runs into one whole one and awards something that never happened;
 * summing a timestamp lands so far in the future that the window it guards never closes again. In
 * both cases the merged player keeps their own value and the other side's is dropped.
 *
 * Note the two `.kill` entries. They read as lifetime totals and are not: `AchievementEvents.kt`
 * assigns them `1` rather than incrementing whenever more than ten seconds have passed since the
 * paired `.time` stamp, so they are burst counts. Excluding the timestamp alone does not protect
 * them, because the achievement reads the count directly and never consults the stamp.
 */
internal val NON_TOTAL_RECORD_KEYS = setOf(
    "record.turret.quill.kill.time",
    "record.turret.zenith.kill.time",
    "record.turret.quill.kill",
    "record.turret.zenith.kill",
    "record.pvp.win.streak.current",
    "record.pvp.defeat.streak.current",
    "record.turret.multikill.current",
    "record.omura.horizon.kill.current",
    "record.explosion.kill.current",
    "record.warp.disconnect.duration",
    "record.time.noafk",
)

/**
 * Whether a `record.*` key may be added to the same key on another account.
 *
 * Both merge paths must use this rather than each spelling out a rule: an account merge and a
 * temporary-data merge that disagree about one key is one of them handing out an achievement the
 * other refuses. The suffix test is a backstop, not the rule - a new window that nobody remembered to
 * put in [NON_TOTAL_RECORD_KEYS] is then dropped rather than summed, and dropping progress is the
 * failure worth having.
 */
internal fun isRunningTotalRecordKey(key: String): Boolean =
    key !in NON_TOTAL_RECORD_KEYS &&
            !key.endsWith(".time") &&
            !key.endsWith(".current") &&
            !key.endsWith(".duration")

internal fun parseLocaleOrDefault(rawLocale: String): String? {
    val normalized = rawLocale.replace('_', '-')
    val locale = Locale.forLanguageTag(normalized)
    if (locale.language.isBlank()) return null

    return if (locale.country.isBlank()) {
        locale.language
    } else {
        "${locale.language}_${locale.country}"
    }
}

@GenerateCode
data class PlayerData(
    val id: UInt,
    var name: String,
    var uuid: String,
    var languageTag: String = "en",
    var blockPlaceCount: Int = 0,
    var blockBreakCount: Int = 0,
    var level: Int = 0,
    var exp: Int = 0,
    var firstPlayed: LocalDateTime,
    var lastPlayed: LocalDateTime,
    var totalPlayed: Int = 0,
    var attackClear: Int = 0,
    var waveClear: Int = 0,
    var pvpWinCount: Short = 0,
    var pvpLoseCount: Short = 0,
    var pvpEliminatedCount: Short = 0,
    var pvpMvpCount: Short = 0,
    var permission: String = "default",
    var accountID: String? = null,
    var accountPW: String? = null,
    var discordID: String? = null,
    var chatMuted: Boolean = false,
    var effectVisibility: Boolean = false,
    var effectLevel: Short? = null,
    var effectColor: String? = null,
    var hideRanking: Boolean = false,
    var strictMode: Boolean = false,
    var lastLoginDate: LocalDateTime,
    var lastLogoutDate: LocalDateTime? = null,
    var lastPlayedWorldName: String? = null,
    var lastPlayedWorldMode: String? = null,
    var isConnected: Boolean = false,
    var isBanned: Boolean = false,
    var banExpireDate: LocalDateTime? = null,
    var attendanceDays: Int = 0,
    /**
     * The `record.*` half of [status] as JSON; read on load, rewritten from the map on every [update].
     *
     * Null on a row written before the column existed.
     */
    var statusData: String? = null
) {
    // Exp
    var expMultiplier: Double = 1.0
    var currentExp: Int = 0
    var currentPlayTime: Int = 0

    // AFK
    var afk = false
    var afkTime: UShort = 0u
    var mousePosition: Float = 0F

    // Logging
    var viewHistoryMode = false
    var mouseTracking = false

    // Used by voting
    val entityId = playerNumber

    // Statistics
    var currentUnitDestroyedCount = 0
    var currentBuildDestroyedCount = 0
    var currentBuildAttackCount = 0

    var currentContribution: Double = 0.0

    // APM (Actions Per Minute)
    var apm = 0
    var apmTimestamps = mutableListOf<Long>()

    // achievements status
    var achievementStatus = mutableListOf<String>()

    var animatedName = false

    var temporary = false

    suspend fun update(): Boolean {
        if (temporary) {
            Log.warn("Player data of $name ($uuid) is temporary, the changes are kept in memory only.")
            return false
        }
        statusData = statusJson.encodeToString(status.filterKeys { it.startsWith(RECORD_PREFIX) })
        return updateRow()
    }

    var player: Playerc = Player.create()

    /**
     * Two kinds of key share this map.
     *
     * `record.*` are the achievement counters, and those are the ones [statusData] carries between
     * sessions and between servers. Everything else - a half-finished hub block selection, the
     * pendingLogin confirmation token, the chat page a player is on - belongs to the session that
     * created it, and is deliberately not persisted: a confirmation that outlives the conversation
     * it belongs to is a confirmation nobody gave.
     *
     * Concurrent because the achievement handlers write it from the game thread while [update] runs
     * from a coroutine.
     */
    val status: MutableMap<String, String> = ConcurrentHashMap()

    init {
        val stored = statusData
        if (!stored.isNullOrBlank()) {
            try {
                status.putAll(statusJson.decodeFromString<Map<String, String>>(stored))
            } catch (e: SerializationException) {
                Log.warn("Unreadable status for $name ($uuid), starting from empty: ${e.message}")
            }
        }
    }

    val bundle: Bundle get() = Bundle(
        if (player.con() != null && !player.locale().isNullOrBlank()) player.locale() else languageTag
    )

    fun send(bundle: Bundle, key: String, vararg args: Any) = send(
        bundle.get(key, *args)
    )

    fun send(key: String, vararg args: Any) {
        val message = bundle.get(key, *args)
        player.sendMessage(message)
        lastReceivedMessage = message
    }

    fun err(key: String, vararg args: Any) {
        val message = "[scarlet]" + bundle.get(key, *args)
        player.sendMessage(message)
        lastReceivedMessage = message
    }

    var lastReceivedMessage: String = ""
        set(value) {
            Log.debug("Plugin send message to ${player.name()}: $value")
            field = value
        }

    /**
     * Send a direct message to the player without looking up a bundle resource.
     * This is useful for sending messages that are not localized.
     * @param message The message to send
     */
    fun sendDirect(message: String) {
        player.sendMessage(message)
        lastReceivedMessage = message
    }
}

/** Create player data */
suspend fun createPlayerData(player: Playerc): PlayerData {
    val rawLocale = player.locale()
    val locale = parseLocaleOrDefault(rawLocale)
    if (locale == null) {
        Log.warn("Invalid player locale detected: '${rawLocale}' (player=${player.name()}, uuid=${player.uuid()})")
        player.sendMessage(Bundle(rawLocale)["event.player.invalid.info"])
    }

    suspendTransaction {
        val notExists = PlayerTable.select(PlayerTable.id)
            .where { PlayerTable.uuid eq player.uuid() }
            .empty()
        if (!notExists) return@suspendTransaction
        PlayerTable.insert {
            it[PlayerTable.name] = player.name()
            it[PlayerTable.uuid] = player.uuid()
            it[PlayerTable.languageTag] = locale ?: "en"
        }
    }

    val entity = suspendTransaction {
        PlayerTable.select(PlayerTable.columns)
            .where { PlayerTable.uuid eq player.uuid() }
            .mapToPlayerDataList().first()
    }

    entity.player = player
    return entity
}

suspend fun createPlayerData(name: String, uuid: String, accountID: String, accountPW: String): PlayerData {
    suspendTransaction {
        PlayerTable.insert {
            it[PlayerTable.name] = name
            it[PlayerTable.uuid] = uuid
            it[PlayerTable.accountID] = accountID
            it[PlayerTable.accountPW] = BCrypt.hashpw(accountPW, BCrypt.gensalt())
        }
    }

    val data = suspendTransaction {
        PlayerTable.select(PlayerTable.columns)
            .where { PlayerTable.uuid eq uuid }
            .mapToPlayerDataList()
            .first()
    }

    return data
}

/** Read player data */
suspend fun getPlayerData(uuid: String): PlayerData? {
    return suspendTransaction {
        PlayerTable.selectAll()
            .where { PlayerTable.uuid eq uuid }
            .mapToPlayerDataList()
    }.firstOrNull()
}

suspend fun getPlayerDataByName(name: String): PlayerData? {
    return suspendTransaction {
        PlayerTable.selectAll()
            .where { PlayerTable.name eq name }
            .mapToPlayerDataList()
    }.firstOrNull()
}

/** Read player data synchronously (for classloader bridge) */
suspend fun getPlayerDataSync(uuid: String): PlayerData? {
    return suspendTransaction {
        PlayerTable.selectAll()
            .where { PlayerTable.uuid eq uuid }
            .mapToPlayerDataList()
    }.firstOrNull()
}

// Used by external plugins
suspend fun getAllPlayerData(): List<PlayerData> {
    return suspendTransaction {
        PlayerTable.selectAll().mapToPlayerDataList()
    }
}

// Used by external plugins
suspend fun getPlayerDataByDiscord(discordID: String): PlayerData? {
    return suspendTransaction {
        PlayerTable.selectAll()
            .where { PlayerTable.discordID eq discordID }
            .mapToPlayerDataList()
    }.firstOrNull()
}

@OptIn(ExperimentalTime::class)
fun createTemporaryPlayerData(player: Playerc): PlayerData {
    val now = Clock.System.now().toLocalDateTime(systemTimezone)
    val data = PlayerData(
        id = 0u,
        name = player.name(),
        uuid = player.uuid(),
        languageTag = parseLocaleOrDefault(player.locale()) ?: "en",
        firstPlayed = now,
        lastPlayed = now,
        lastLoginDate = now
    )
    data.player = player
    return data
}

suspend fun deletePlayerData(uuid: String): Boolean = suspendTransaction {
    val id = PlayerTable.select(PlayerTable.id)
        .where { PlayerTable.uuid eq uuid }
        .map { it[PlayerTable.id] }
        .firstOrNull()
    if (id != null) {
        AchievementTable.deleteWhere { AchievementTable.playerId eq id }
        ContributionTable.deleteWhere { ContributionTable.playerId eq id }
    }
    PlayerTable.deleteWhere { PlayerTable.uuid eq uuid } > 0
}

suspend fun rebindAccountUuid(id: UInt, newUuid: String): Boolean = suspendTransaction {
    PlayerTable.update({ PlayerTable.id eq id }) {
        it[PlayerTable.uuid] = newUuid
    } > 0
}