package essential.common.util

import arc.util.Log
import arc.util.Strings
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.mapToPlayerDataList
import essential.common.database.table.PlayerTable
import essential.common.players
import mindustry.gen.Groups
import mindustry.gen.Playerc
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

object PlayerLookup {
    const val NOT_FOUND = "player.not.found"
    const val NOT_REGISTERED = "player.not.registered"
    const val AMBIGUOUS = "player.lookup.ambiguous"
    const val AMBIGUOUS_OFFLINE = "player.lookup.ambiguous.offline"
    const val TRUNCATED = "player.lookup.truncated"
    const val SPACES = "player.lookup.spaces"

    const val SCAN_LIMIT = 5000

    private const val MAX_CANDIDATES = 10
    private const val MAX_NAME_LENGTH = 24
    private const val UUID_PREVIEW = 8

    sealed interface Result<out T> {
        data class Found<T>(val value: T) : Result<T>
        data class Ambiguous(
            val candidates: List<String>,
            val spacedNames: Boolean = false,
            val offline: Boolean = false,
            val truncated: Boolean = false
        ) : Result<Nothing>

        data class NotFound(val truncated: Boolean = false) : Result<Nothing>
    }

    fun shortName(name: String): String {
        val plain = Strings.stripColors(name)
        return if (plain.length > MAX_NAME_LENGTH) plain.take(MAX_NAME_LENGTH - 1) + "…" else plain
    }

    fun sessionId(uuid: String): Int? = players.find { it.uuid == uuid }?.entityId

    private fun normalize(query: String) = Strings.stripColors(query).trim()

    private fun isOnline(data: PlayerData) = data.player.con() != null

    private fun nameOf(data: PlayerData) =
        if (isOnline(data)) data.player.plainName() else Strings.stripColors(data.name)

    private fun labelOf(data: PlayerData) =
        if (isOnline(data)) "[${data.entityId}] ${shortName(nameOf(data))}"
        else "${Strings.stripColors(data.name)} (${data.uuid.take(UUID_PREVIEW)})"

    private fun <T> pick(
        items: List<T>,
        query: String,
        name: (T) -> String,
        label: (T) -> String,
        exactOnly: Boolean = false,
        truncated: Boolean = false,
        offline: (T) -> Boolean = { false }
    ): Result<T> {
        fun ambiguous(candidates: List<T>) = Result.Ambiguous(
            candidates.take(MAX_CANDIDATES).map(label),
            candidates.any { name(it).contains(' ') },
            candidates.any(offline),
            truncated
        )

        val exact = items.filter { name(it).equals(query, true) }
        if (exact.size == 1) return Result.Found(exact.first())
        if (exact.isNotEmpty()) return ambiguous(exact)
        if (exactOnly) return Result.NotFound(truncated)

        val prefix = items.filter { name(it).startsWith(query, true) }
        if (prefix.size == 1) return Result.Found(prefix.first())
        if (prefix.isNotEmpty()) return ambiguous(prefix)

        val partial = items.filter { name(it).contains(query, true) }
        if (partial.size == 1) return Result.Found(partial.first())
        if (partial.isEmpty()) return Result.NotFound(truncated)
        return ambiguous(partial)
    }

    fun findOnline(query: String): Result<Playerc> {
        val text = normalize(query)
        if (text.isEmpty()) return Result.NotFound()

        if (text.startsWith("#")) {
            val id = text.drop(1).toIntOrNull() ?: return Result.NotFound()
            return byEntityId(id)?.let { Result.Found(it) } ?: Result.NotFound()
        }

        val id = text.toIntOrNull()
        if (id != null) byEntityId(id)?.let { return Result.Found(it) }

        val online = Groups.player.toList()
        online.find { it.uuid() == text }?.let { return Result.Found(it) }

        return pick(online, text, { it.plainName() }, {
            val session = sessionId(it.uuid())
            if (session != null) "[$session] ${shortName(it.plainName())}" else shortName(it.plainName())
        })
    }

    private fun byEntityId(id: Int): Playerc? = players.find { it.entityId == id }?.player

    suspend fun findOffline(query: String): Result<PlayerData> = lookup(query, false)

    suspend fun findExact(query: String): Result<PlayerData> = lookup(query, true)

    private suspend fun lookup(query: String, exactOnly: Boolean): Result<PlayerData> {
        val text = normalize(query)
        if (text.isEmpty()) return Result.NotFound()

        if (text.startsWith("#")) {
            val id = text.drop(1).toIntOrNull() ?: return Result.NotFound()
            return players.find { it.entityId == id }?.let { Result.Found(it) } ?: Result.NotFound()
        }

        text.toIntOrNull()?.let { id -> players.find { it.entityId == id }?.let { return Result.Found(it) } }
        players.find { it.uuid == text }?.let { return Result.Found(it) }

        val (rows, truncated) = offlineRows(text)
        rows.find { it.uuid == text }?.let { return Result.Found(it) }

        val online = players.toList()
        val union = online + rows.filterNot { row -> online.any { it.uuid == row.uuid } }
        return pick(union, text, ::nameOf, ::labelOf, exactOnly, truncated) { !isOnline(it) }
    }

    private suspend fun offlineRows(text: String): Pair<List<PlayerData>, Boolean> {
        val pattern = text.lowercase().escapeLike()
        val matched = suspendTransaction {
            PlayerTable.selectAll().where {
                (PlayerTable.uuid eq text) or (PlayerTable.name.lowerCase() like LikePattern("%$pattern%", '\\'))
            }.mapToPlayerDataList()
        }
        if (matched.isNotEmpty()) return matched to false

        val scanned = suspendTransaction {
            PlayerTable.selectAll().limit(SCAN_LIMIT).mapToPlayerDataList()
        }
        return scanned.filter { Strings.stripColors(it.name).contains(text, true) } to (scanned.size >= SCAN_LIMIT)
    }

    fun online(query: String, playerData: PlayerData): Playerc? = report(findOnline(query), query, playerData, NOT_FOUND)

    fun online(query: String): Playerc? = report(findOnline(query), query, null, NOT_FOUND)

    fun onlineData(query: String, playerData: PlayerData): PlayerData? = onlineDataOf(query, playerData)

    fun onlineData(query: String): PlayerData? = onlineDataOf(query, null)

    suspend fun offline(query: String, playerData: PlayerData): PlayerData? = offlineOf(query, playerData)

    suspend fun offline(query: String): PlayerData? = offlineOf(query, null)

    fun ambiguous(result: Result<*>, query: String, sender: PlayerData?): Boolean {
        if (result !is Result.Ambiguous) return false
        report(result, query, sender, NOT_FOUND)
        return true
    }

    private fun onlineDataOf(query: String, sender: PlayerData?): PlayerData? {
        val result = findOnline(query)
        if (result is Result.Found) {
            val data = players.find { it.uuid == result.value.uuid() }
            if (data == null || data.temporary) return report(Result.NotFound(), query, sender, NOT_REGISTERED)
            return data
        }
        report(result, query, sender, NOT_FOUND)
        return null
    }

    private suspend fun offlineOf(query: String, sender: PlayerData?): PlayerData? {
        val result = findOffline(query)
        if (result is Result.Found && result.value.temporary) {
            return report(Result.NotFound(), query, sender, NOT_REGISTERED)
        }
        val missing = if (result is Result.NotFound && findOnline(query) is Result.Found) NOT_REGISTERED else NOT_FOUND
        return report(result, query, sender, missing)
    }

    private fun <T> report(result: Result<T>, query: String, sender: PlayerData?, missing: String): T? {
        val bundle = Bundle()
        fun warn(key: String, vararg args: Any) {
            if (sender != null) sender.err(key, *args) else Log.warn(bundle.get(key, *args))
        }

        when (result) {
            is Result.Found -> return result.value

            is Result.Ambiguous -> {
                val key = if (result.offline) AMBIGUOUS_OFFLINE else AMBIGUOUS
                warn(key, shortName(query), result.candidates.joinToString("\n"))
                if (result.spacedNames) warn(SPACES)
                if (result.truncated) warn(TRUNCATED, SCAN_LIMIT)
            }

            is Result.NotFound -> {
                warn(missing)
                if (result.truncated) warn(TRUNCATED, SCAN_LIMIT)
            }
        }
        return null
    }
}
