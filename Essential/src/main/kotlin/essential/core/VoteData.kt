package essential.core

import essential.common.database.data.PlayerData
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Playerc
import mindustry.maps.Map

/** Core-side request model consumed by the optional vote service. */
data class VoteData(
    var type: VoteType,
    var target: Playerc? = null,
    var targetUUID: String? = null,
    var reason: String? = null,
    var map: Map? = null,
    var wave: Int? = null,
    var starter: PlayerData,
    // Ruled in answers/9-2.md: on a PvP map the electorate is the starter's own team, never
    // defaultTeam - `kick` and `gg` used to hand-write this and the other five vote types left
    // it as "whoever is on defaultTeam", which is nobody's intent. See VoteSystem.check().
    var team: Team = if (Vars.state.rules.pvp) starter.player.team() else Vars.state.rules.defaultTeam,
)

enum class VoteType {
    Kick, Map, GameOver, Skip, Back, Random, Draw,
}
