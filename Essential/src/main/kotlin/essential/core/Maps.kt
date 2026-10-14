package essential.core

import arc.Events
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
 * Ends the map as a surrender to the wave team, so the usual game-over flow moves on: to [next] when it is given,
 * else to the map the server picks. How a passed /rtv, `vote map` and `vote gg` change the map.
 */
fun endMap(next: Map? = null) {
    isSurrender = true
    if (next != null) Vars.maps.setNextMapOverride(next)
    Events.fire(GameOverEvent(Vars.state.rules.waveTeam))
}
