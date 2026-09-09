package essential.common.database.data.plugin

import kotlinx.serialization.Serializable
import mindustry.Vars
import mindustry.world.Tile

@Serializable
data class WarpZone(
    val mapName: String,
    val start: Int,
    val finish: Int,
    val click: Boolean,
    val ip: String,
    val port: Int
) {
    /**
     * Null while the stored position lies off the currently loaded map. `World.tile` is annotated
     * `arc.util.Nullable`, which Kotlin does not read, so declaring these non-null turned that null
     * into a throw inside the getter itself - where no caller could guard it.
     */
    val startTile: Tile? get() = Vars.world.tile(start)
    val finishTile: Tile? get() = Vars.world.tile(finish)
}
