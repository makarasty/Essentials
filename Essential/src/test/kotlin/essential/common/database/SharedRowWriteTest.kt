package essential.common.database

import PluginTest.Companion.loadGame
import essential.common.database.data.createPlayerData
import essential.common.database.data.getPlayerData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Six servers run against one database and each holds its own copy of a row from the moment it read
 * it. This stands in for that: two copies of the same row are loaded, each changes a different part
 * of it, and both are saved. Whatever the second save does not touch has to survive.
 */
class SharedRowWriteTest {

    @Test
    fun anUnrelatedSaveDoesNotRevertAnotherServersBan() {
        loadGame(true)
        runBlocking {
            // A fresh uuid per run, so a database left over from an earlier run cannot make this pass
            // vacuously with the ban and the count already in place. players.uuid is varchar(25).
            val uuid = "srw-" + System.nanoTime()
            val serverA = getPlayerData(uuid) ?: createPlayerData("shared-row", uuid, "shared-row", "shared-row")
            assertNotNull(serverA)

            // Server B loads its own copy of the same row and bans the player.
            val serverB = getPlayerData(uuid)
            assertNotNull(serverB)
            assertEquals(serverA.id, serverB.id, "the two copies must be of the same row")
            serverB.isBanned = true
            assertTrue(serverB.update(), "server B could not write the ban")

            // Server A, still holding the copy it read before the ban, saves for an unrelated reason.
            serverA.blockPlaceCount = 42
            assertTrue(serverA.update(), "server A could not write its own change")

            val stored = getPlayerData(uuid)
            assertNotNull(stored)
            assertEquals(42, stored.blockPlaceCount, "server A's own change was lost")
            assertTrue(stored.isBanned, "server A's unrelated save reverted the ban written by server B")
        }
    }

}
