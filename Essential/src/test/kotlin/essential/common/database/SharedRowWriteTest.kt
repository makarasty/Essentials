package essential.common.database

import PluginTest.Companion.loadGame
import essential.common.database.data.createPlayerData
import essential.common.database.data.getPlayerData
import essential.common.database.data.getPluginData
import essential.common.pluginData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Six servers run against one database and each holds its own copy of a row from the moment it read
 * it. These two tests stand in for that: two copies of the same row are loaded, each changes a
 * different part of it, and both are saved. Whatever the second save does not touch has to survive.
 */
class SharedRowWriteTest {

    @Test
    fun anUnrelatedSaveDoesNotRevertAnotherServersBan() {
        loadGame(true)
        runBlocking {
            // A fresh uuid per run, so a database left over from an earlier run cannot make this pass
            // vacuously with the ban and the count already in place. players.uuid is varchar(25).
            val uuid = "srw-" + System.nanoTime()
            // The name carries the same nonce, and that is not tidying. players.name has a unique index
            // of its own, so a fixed name here is a second row this insert can collide with. It could
            // not collide while the suite ran on a legacy-shaped schema that had no unique index on
            // name at all - which is why this went red exactly once, over a database.mv.db a
            // half-dead run had left behind. stopPlugin() now really deletes that file, so the leftover
            // row is gone; but the unique index is now really there, and a run that dies before any
            // stopPlugin() still leaves the file. That is a newer failure than the one just removed.
            val serverA = getPlayerData(uuid) ?: createPlayerData(uuid, uuid, uuid, uuid)
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

    @Test
    fun anUnrelatedSaveDoesNotDropAnotherServersTempBan() {
        loadGame(true)
        runBlocking {
            val banned = "shared-row-write-test-uuid"
            val name = "shared-row-write-test-name"

            // Server A is the process-wide copy loaded at boot.
            val serverA = pluginData

            try {
                // Server B loads its own copy and issues a temp ban.
                val serverB = getPluginData()
                assertNotNull(serverB)
                assertEquals(serverA.id, serverB.id, "the two copies must be of the same row")
                assertTrue(serverA.data !== serverB.data, "the two copies must not share the blob")
                serverB.data.tempBans[banned] = "2099-01-01T00:00"
                assertTrue(serverB.update(), "server B could not write the temp ban")

                // Server A edits something else in the same blob and saves.
                assertTrue(
                    !serverA.data.tempBans.containsKey(banned),
                    "server A's copy already knows the temp ban, so this test would prove nothing"
                )
                serverA.data.blacklistedNames.add(name)
                assertTrue(serverA.update(), "server A could not write its own change")

                val stored = getPluginData()
                assertNotNull(stored)
                assertTrue(stored.data.blacklistedNames.contains(name), "server A's own change was lost")
                assertTrue(
                    stored.data.tempBans.containsKey(banned),
                    "server A's unrelated save erased the temp ban issued on server B"
                )
            } finally {
                // The blob is process-global state every later test reads, so put it back even when an
                // assertion above fails: a blacklisted name filters joins and a temp ban outlives the JVM.
                serverA.data.blacklistedNames.remove(name)
                serverA.data.tempBans.remove(banned)
                serverA.update()
            }
        }
    }
}
