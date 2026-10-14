package essential.core

import arc.Events
import arc.files.Fi
import essential.common.database.WorldHistoryBuffer
import essential.common.database.data.clearWorldHistory
import essential.common.isSurrender
import essential.core.Main.Companion.scope
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.game.EventType.GameOverEvent
import mindustry.game.Gamemode
import mindustry.maps.Map
import mindustry.net.WorldReloader

/**
 * The server's maps in the order /maps numbers them: a map's "ID" there is its index here. Every command that
 * takes a map ID (/maps, /rtv, /changemap, /vote map, /nextmap) reads it from this one list, so IDs always match.
 */
fun mapsById(): List<Map> = Vars.maps.all().sortedBy { it.name() }

/**
 * The map [query] names, for every command that takes one (/rtv, /changemap, /vote map, /nextmap). A whole number is
 * an ID and nothing else: the map at that index in [mapsById], or none. Any other query is a name, matched against
 * plain names (no colour tags) ignoring case: the map named exactly that, else the first in that same order whose
 * name contains it - so "fortress" is Fortress even when Desert Fortress comes first.
 */
fun findMap(query: String): Map? {
    val maps = mapsById()
    query.toIntOrNull()?.let { return maps.getOrNull(it) }
    return maps.find { it.plainName().equals(query, ignoreCase = true) }
        ?: maps.find { it.plainName().contains(query, ignoreCase = true) }
}

/** The map in [mapsById] stored in [file], or null once it is gone. A map is held by file: a map list reload makes new objects. */
fun mapByFile(file: Fi): Map? = mapsById().find { it.file == file }

/** Whether [map] is the one being played, by file. A vote to move on to it would only restart it. */
fun isCurrentMap(map: Map): Boolean = map.file == Vars.state.map.file

/**
 * Replaces the world with [map] now, in [mode], without a game over: /changemap, and `vote map` from a player alone
 * on the server.
 */
fun loadMapNow(map: Map, mode: Gamemode) {
    val reloader = WorldReloader()
    reloader.begin()
    Vars.world.loadMap(map, map.applyRules(mode))
    Vars.state.rules = Vars.state.map.applyRules(mode)
    Vars.logic.play()
    reloader.end()
    discardWorldHistory()
}

/**
 * History rows are keyed by tile coordinates and by nothing else, so across a map change they
 * become claims about a map that is no longer loaded and a rollback rebuilds and removes real
 * blocks from them. The game over handler already clears them; a map changed directly never
 * fires one. The flush first is so that rows still sitting in the buffer cannot land after the
 * table has been emptied.
 */
private fun discardWorldHistory() {
    scope.launch {
        WorldHistoryBuffer.flush()
        clearWorldHistory()
    }
}

/** The /nextmap round's votes per map, most first. */
fun nextMapTally(): List<Pair<Map, Int>> = mapVotes.values.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

/** The map the /nextmap round's votes pick - the most voted - or null with no votes. */
fun nextMapWinner(): Map? = nextMapTally().firstOrNull()?.first

/**
 * Ends the map as a surrender to the wave team, so the usual game-over flow moves on: to [next] when it is given,
 * else to the map the server picks. How a passed /rtv, `vote map` and `vote gg` change the map.
 */
fun endMap(next: Map? = null) {
    isSurrender = true
    if (next != null) {
        Vars.maps.setNextMapOverride(next)
        // The game-over handler would announce the /nextmap tally's winner, which [next] replaces
        mapVotes.clear()
    }
    Events.fire(GameOverEvent(Vars.state.rules.waveTeam))
}
