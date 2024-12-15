package essential.core.service.vote

import essential.core.DB
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Playerc
import mindustry.maps.Map

data class VoteData (
    var type: VoteType,
    var target: Playerc? = null,
    var targetUUID: String? = null,
    var reason: String? = null,
    var map: Map? = null,
    var wave: Int? = null,
    var starter: DB.PlayerData,
    var team: Team = Vars.state.rules.defaultTeam
)