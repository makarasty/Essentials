package essential.core

import arc.Events
import arc.files.Fi
import essential.common.isSurrender
import mindustry.Vars
import mindustry.game.EventType.GameOverEvent
import mindustry.maps.Map

/**
 * The server's maps in the order /maps numbers them: a map's "ID" there is its index here. Every command that
 * takes a map ID (/maps, /rtv, /changemap, /vote map, /nextmap) reads it from this one list, so IDs always match.
 */
fun mapsById(): List<Map> = Vars.maps.all().sortedBy { it.name() }

/**
 * The map [query] names, for every command that takes one: the map with that ID in [mapsById] when it is one, else
 * the first whose plain name contains it, ignoring case.
 */
fun findMap(query: String): Map? {
    val maps = mapsById()
    return query.toIntOrNull()?.let { maps.getOrNull(it) } ?: maps.find { it.plainName().contains(query, ignoreCase = true) }
}

/** The map in [mapsById] stored in [file], or null once it is gone. A map is held by file: a map list reload makes new objects. */
fun mapByFile(file: Fi): Map? = mapsById().find { it.file == file }

/** Whether [map] is the one being played, by file. A vote to move on to it would only restart it. */
fun isCurrentMap(map: Map): Boolean = map.file == Vars.state.map.file

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
