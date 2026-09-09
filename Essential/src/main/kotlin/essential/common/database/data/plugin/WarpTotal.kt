package essential.common.database.data.plugin

import kotlinx.serialization.Serializable
import mindustry.Vars
import mindustry.world.Tile

@Serializable
data class WarpTotal(val mapName: String, val pos: Int, var totalPlayers: Int, var numberSize: Int) {
    /** Null while the stored position is off the currently loaded map, as on [WarpZone]. */
    val tile: Tile? get() = Vars.world.tile(pos)
}
