package essential.common

import arc.Core
import arc.files.Fi
import arc.func.Cons
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.database.data.PluginData
import essential.common.util.toHString
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeMark
import kotlin.time.TimeSource


/** Plugin version */
val PLUGIN_VERSION: String get() {
    val file = PluginData::class.java.getResourceAsStream("/plugin.json")
    file.use {
        val jsonString = it.bufferedReader().readText()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        return jsonObject["version"]?.jsonPrimitive?.content ?: "unknown"
    }
}

/** Plugin message bundle */
val bundle = Bundle()

/** Plugin data folder path */
val rootPath: Fi = Core.settings.dataDirectory.child("mods/Essentials/")

/** Kotlin TimeSource */
val timeSource = TimeSource.Monotonic

/** Server start time */
private val startupTime = timeSource.markNow()

/** Map start time */
var mapStartTime = timeSource.markNow()

/** Game over count */
var gameOverCount = 0

/** Server uptime */
val uptime : String get() = (timeSource.markNow() - startupTime).toHString()

/** Elapsed time on current map */
val playTime : String get() = (timeSource.markNow() - mapStartTime).toHString()

/** Next vote availability time */
var nextVoteAvailable : TimeMark = timeSource.markNow()

/** Remaining vote cooldown per player (UUID -> TimeMark) */
var voterCooldown = ConcurrentHashMap<String, TimeMark>()

/** Whether a vote is in progress */
var isVoting = false

/** Whether plugin-induced cheats are enabled (reset on map change) */
var isCheated = false

/** Whether surrender is active (reset on map change) */
var isSurrender = false

/** Player data list */
val players = PlayerList()

/**
 * The online players, plus a uuid index every mutator keeps in step, so [byUuid] - behind
 * findPlayerData, which nearly every player action goes through - is a map lookup and not a scan.
 *
 * The index is rebuilt whole after each change rather than patched: changes are a join or a leave on
 * a list a few dozen long, and a rebuild keeps the answer `find` gave when a uuid is in the list
 * twice (the first one). Mutation and rebuild share one lock so two threads cannot interleave them;
 * readers see the last published index without taking it.
 */
class PlayerList : CopyOnWriteArrayList<PlayerData>() {
    private val lock = Any()

    @Volatile
    private var index: Map<String, PlayerData> = emptyMap()

    /** The first player in the list with [uuid], as `find { it.uuid == uuid }` would return. */
    fun byUuid(uuid: String): PlayerData? {
        val hit = index[uuid] ?: return null
        // A PlayerData's uuid is a var (an account login moves it to the device's uuid); a moved one
        // is found the slow way rather than under the uuid it was indexed by.
        return if (hit.uuid == uuid) hit else find { it.uuid == uuid }
    }

    private inline fun <T> mutate(change: () -> T): T = synchronized(lock) {
        val result = change()
        val next = HashMap<String, PlayerData>(size * 2)
        for (data in this) next.putIfAbsent(data.uuid, data)
        index = next
        result
    }

    override fun add(element: PlayerData): Boolean = mutate { super.add(element) }
    override fun add(index: Int, element: PlayerData) = mutate { super.add(index, element) }
    override fun addAll(elements: Collection<PlayerData>): Boolean = mutate { super.addAll(elements) }
    override fun addAll(index: Int, elements: Collection<PlayerData>): Boolean = mutate { super.addAll(index, elements) }
    override fun addIfAbsent(element: PlayerData): Boolean = mutate { super.addIfAbsent(element) }
    override fun addAllAbsent(c: Collection<PlayerData>): Int = mutate { super.addAllAbsent(c) }
    override fun set(index: Int, element: PlayerData): PlayerData = mutate { super.set(index, element) }
    override fun remove(element: PlayerData): Boolean = mutate { super.remove(element) }
    override fun removeAt(index: Int): PlayerData = mutate { super.removeAt(index) }
    override fun removeAll(elements: Collection<PlayerData>): Boolean = mutate { super.removeAll(elements) }
    override fun retainAll(elements: Collection<PlayerData>): Boolean = mutate { super.retainAll(elements) }
    override fun clear() = mutate { super.clear() }
    override fun replaceAll(operator: java.util.function.UnaryOperator<PlayerData>) = mutate { super.replaceAll(operator) }

    // Called 60 times a second by Trigger with nothing to remove: only a real removal pays the rebuild.
    override fun removeIf(filter: java.util.function.Predicate<in PlayerData>): Boolean = synchronized(lock) {
        super.removeIf(filter).also { removed -> if (removed) mutate { } }
    }
}

/** System time zone */
val systemTimezone = TimeZone.currentSystemDefault()

/**
 * Per-session entity id sequence, a process-local counter and not shared/serialised state - it is
 * a file-level global, not a field of the [essential.common.database.data.PluginData] the six
 * servers share. Atomic because the old plain Int was read at PlayerData construction time, inside
 * the join coroutine, and only incremented later on the game thread: two players joining close
 * together could be constructed before either increment ran, and both got entityId 0. #-lookups
 * then resolved to whichever of them sorted first, silently - task-067.
 */
val playerNumber = AtomicInteger(0)

/** Players who left during a match */
var offlinePlayers = mutableListOf<PlayerData>()

/** Plugin data */
lateinit var pluginData: PluginData

/** Event listener registry that tracks all registered event listeners */
class EventListenerRegistry : Iterable<Pair<Class<*>, Cons<*>>> {
    private val listeners = CopyOnWriteArrayList<Pair<Class<*>, Cons<*>>>()

    operator fun set(key: Class<*>, value: Cons<*>) {
        listeners.add(key to value)
    }

    override fun iterator(): Iterator<Pair<Class<*>, Cons<*>>> = listeners.iterator()

    fun remove(listener: Cons<*>) {
        listeners.removeIf { it.second === listener }
    }

    fun clear() {
        listeners.clear()
    }
}

/** Event listeners registered by the plugin */
val eventListeners = EventListenerRegistry()

/** Print plugin data summary */
fun getPluginDataInfo(): String {
    return """
        |rootPath: ${rootPath.absolutePath()}
        |startupTime: $startupTime
        |uptime: $uptime
        |playTime: $playTime
        |nextVoteAvailable: $nextVoteAvailable
        |voterCooldown: $voterCooldown
        |isVoting: $isVoting
        |isCheated: $isCheated
        |isSurrender: $isSurrender
        |offlinePlayers: ${offlinePlayers.size}
        |pluginData: $pluginData
    """.trimMargin()
}
