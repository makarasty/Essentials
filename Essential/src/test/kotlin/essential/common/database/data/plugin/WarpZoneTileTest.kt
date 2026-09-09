package essential.common.database.data.plugin

import PluginTest.Companion.loadGame
import arc.math.geom.Point2
import mindustry.Vars
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A warp zone stores raw packed coordinates and is matched only by map name, so a zone configured
 * on a large map resolves to nothing once a smaller map of the same name is loaded. Reading the
 * tiles for such a zone must return null rather than throw: the getters are the only place that can
 * decide it, and every caller reads them while iterating the whole warp list.
 */
class WarpZoneTileTest {
    @BeforeTest
    fun setup() {
        loadGame()
    }

    private fun zone(start: Int, finish: Int) = WarpZone(
        mapName = Vars.state.map.name(),
        start = start,
        finish = finish,
        click = false,
        ip = "127.0.0.1",
        port = 6567
    )

    @Test
    fun aPositionOffTheCurrentMapReadsAsNull() {
        // Without this the test passes on an unloaded world, where every position is out of bounds
        // and every lookup returns null for a reason that has nothing to do with the property.
        assertTrue(Vars.world.width() > 0 && Vars.world.height() > 0, "no map is loaded")

        val offMap = Point2.pack(Vars.world.width() + 64, Vars.world.height() + 64)
        val zone = zone(offMap, offMap)

        assertNull(zone.startTile, "a start position off the current map has no tile")
        assertNull(zone.finishTile, "a finish position off the current map has no tile")
    }

    @Test
    fun aPositionOnTheCurrentMapStillResolves() {
        val start = (Vars.world.tile(1, 1) ?: fail("the test map has no tile at 1,1")).pos()
        val finish = (Vars.world.tile(5, 5) ?: fail("the test map has no tile at 5,5")).pos()
        val zone = zone(start, finish)

        assertEquals(start, zone.startTile?.pos())
        assertEquals(finish, zone.finishTile?.pos())
    }
}
