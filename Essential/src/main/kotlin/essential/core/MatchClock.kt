package essential.core

import essential.common.timeSource
import essential.common.util.toHString
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Groups
import kotlin.time.TimeMark

/**
 * Time since the game on the current map actually became a game.
 *
 * `playTime` counts from the moment the map loaded, which on a PvP server means the
 * clock is already running while nobody is playing. This one starts when the map is
 * being contested: on PvP two teams with a core and a player, elsewhere a single
 * player is enough. It stops and clears when the server empties, and the map load
 * and the game over reset it.
 */
object MatchClock {
    private var start: TimeMark? = null

    val text: String get() = start?.elapsedNow()?.toHString() ?: ""

    fun reset() {
        start = null
    }

    fun update() {
        if (!Vars.state.isPlaying) {
            start = null
            return
        }
        if (live()) {
            if (start == null) start = timeSource.markNow()
        } else if (Groups.player.size() == 0) {
            start = null
        }
    }

    private fun live(): Boolean =
        if (Vars.state.rules.pvp) contestedTeams() >= 2 else Groups.player.size() > 0

    /** Teams that hold a core and have at least one player on them. */
    private fun contestedTeams(): Int = Groups.player
        .filter { it.team() != Team.derelict && it.team().cores().any() }
        .map { it.team() }
        .distinct()
        .size
}
