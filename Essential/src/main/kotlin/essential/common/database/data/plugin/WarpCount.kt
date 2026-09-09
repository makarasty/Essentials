package essential.common.database.data.plugin

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import mindustry.Vars
import mindustry.world.Tile

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class WarpCount(
    val mapName: String,
    val pos: Int,
    val ip: String,
    val port: Int
) {
    /**
     * This server's own view of the remote server, and out of the constructor - and so out of the
     * generated `equals` - because `PluginData.mergedOnto` identifies elements by equality: with either
     * of these inside it, every refresh read as one element deleted and a different one added, and two
     * servers on a same-named map grew duplicate rows in the shared blob without bound. The digit width
     * has to move too: it is derived from the count, so leaving it behind reproduces the same churn
     * every time the count crosses 9 or 99.
     *
     * `@EncodeDefault` because the six servers upgrade one at a time and a jar predating this change
     * declares both as required constructor fields. `Json` does not emit a property still holding its
     * default, and zero is the ordinary state of a player count, so without this an old jar fails to
     * decode the whole plugin data blob rather than one row.
     */
    @EncodeDefault
    var players = 0

    @EncodeDefault
    var numberSize = 1

    /** Null while the stored position is off the currently loaded map, as on [WarpZone]. */
    val tile: Tile? get() = Vars.world.tile(pos)
}
