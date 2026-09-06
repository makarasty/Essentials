package essential.common.util

import arc.util.Log
import arc.util.Strings
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.getPlayerData
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
    const val SPACES = "player.lookup.spaces"

    private const val MAX_CANDIDATES = 10
    private const val MAX_NAME_LENGTH = 24

    sealed interface Result<out T> {
        data class Found<T>(val value: T) : Result<T>
        data class Ambiguous(val candidates: List<String>, val spacedNames: Boolean = false) : Result<Nothing>
        data object NotFound : Result<Nothing>
    }

    fun shortName(name: String): String {
        val plain = Strings.stripColors(name)
        return if (plain.length > MAX_NAME_LENGTH) plain.take(MAX_NAME_LENGTH - 1) + "…" else plain
    }

    fun sessionId(uuid: String): Int? = players.find { it.uuid == uuid }?.entityId

    private fun normalize(query: String) = Strings.stripColors(query).trim()

    private fun <T> pick(items: List<T>, query: String, name: (T) -> String, label: (T) -> String): Result<T> {
        val exact = items.filter { name(it).equals(query, true) }
        if (exact.size == 1) return Result.Found(exact.first())

        val prefix = items.filter { name(it).startsWith(query, true) }
        if (prefix.size == 1) return Result.Found(prefix.first())

        val partial = items.filter { name(it).contains(query, true) }
        if (partial.size == 1) return Result.Found(partial.first())

        val candidates = when {
            exact.isNotEmpty() -> exact
            prefix.isNotEmpty() -> prefix
            else -> partial
        }
        if (candidates.isEmpty()) return Result.NotFound
        return Result.Ambiguous(candidates.take(MAX_CANDIDATES).map(label), candidates.any { name(it).contains(' ') })
    }

    fun findOnline(query: String): Result<Playerc> {
        val text = normalize(query)
        if (text.isEmpty()) return Result.NotFound

        if (text.startsWith("#")) {
            val id = text.drop(1).toIntOrNull() ?: return Result.NotFound
            return byEntityId(id)?.let { Result.Found(it) } ?: Result.NotFound
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

    suspend fun findOffline(query: String): Result<PlayerData> {
        val text = normalize(query)
        if (text.isEmpty()) return Result.NotFound

        when (val online = findOnline(text)) {
            is Result.Found -> {
                val data = players.find { it.uuid == online.value.uuid() } ?: getPlayerData(online.value.uuid())
                return if (data != null) Result.Found(data) else Result.NotFound
            }

            is Result.Ambiguous -> return online
            Result.NotFound -> {}
        }

        val pattern = text.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = suspendTransaction {
            PlayerTable.selectAll().where {
                (PlayerTable.uuid eq text) or
                    (PlayerTable.name.lowerCase() eq text.lowercase()) or
                    (PlayerTable.name.lowerCase() like LikePattern("%$pattern%", '\\'))
            }.mapToPlayerDataList()
        }

        rows.find { it.uuid == text }?.let { return Result.Found(it) }
        return pick(rows, text, { Strings.stripColors(it.name) }, {
            "${shortName(it.name)} (${it.lastLoginDate.date})"
        })
    }

    fun online(query: String, playerData: PlayerData): Playerc? = report(findOnline(query), query, playerData, NOT_FOUND)

    fun online(query: String): Playerc? = report(findOnline(query), query, null, NOT_FOUND)

    fun onlineData(query: String, playerData: PlayerData): PlayerData? = onlineDataOf(query, playerData)

    fun onlineData(query: String): PlayerData? = onlineDataOf(query, null)

    suspend fun offline(query: String, playerData: PlayerData): PlayerData? = offlineOf(query, playerData)

    suspend fun offline(query: String): PlayerData? = offlineOf(query, null)

    private fun onlineDataOf(query: String, sender: PlayerData?): PlayerData? {
        val result = findOnline(query)
        if (result is Result.Found) {
            return players.find { it.uuid == result.value.uuid() }
                ?: report(Result.NotFound, query, sender, NOT_REGISTERED)
        }
        report(result, query, sender, NOT_FOUND)
        return null
    }

    private suspend fun offlineOf(query: String, sender: PlayerData?): PlayerData? {
        val result = findOffline(query)
        val missing = if (result is Result.NotFound && findOnline(query) is Result.Found) NOT_REGISTERED else NOT_FOUND
        return report(result, query, sender, missing)
    }

    private fun <T> report(result: Result<T>, query: String, sender: PlayerData?, missing: String): T? {
        when (result) {
            is Result.Found -> return result.value

            is Result.Ambiguous -> {
                val list = result.candidates.joinToString("\n")
                if (sender != null) {
                    sender.err(AMBIGUOUS, shortName(query), list)
                    if (result.spacedNames) sender.err(SPACES)
                } else {
                    val bundle = Bundle()
                    Log.warn(bundle[AMBIGUOUS, shortName(query), list])
                    if (result.spacedNames) Log.warn(bundle[SPACES])
                }
            }

            Result.NotFound -> {
                if (sender != null) sender.err(missing) else Log.warn(Bundle()[missing])
            }
        }
        return null
    }
}
