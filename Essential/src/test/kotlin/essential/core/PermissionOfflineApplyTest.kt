package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.waitUntil
import essential.common.database.data.getPlayerData
import essential.common.permission.Permission
import essential.common.rootPath
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What applying permission_user.yaml does to the rows of the players it names who are not online.
 *
 * This ran as one coroutine and one transaction per entry, so a file with a few thousand entries
 * queued a few thousand connection acquisitions against a pool of five and most of them timed out.
 * The obvious repair - one transaction for the whole file - is worse in a way no boot would ever
 * show you: PlayerTable.name is a unique index, so a single entry whose `name:` collides with
 * another row rolls the whole file back and every other entry is silently lost.
 *
 * So the file is applied a few entries at a time, each in its own transaction, and this pins the
 * property that costs.
 */
class PermissionOfflineApplyTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    @Test
    fun permission_oneEntryThatCannotBeWrittenDoesNotTakeTheRestOfTheFileWithIt() {
        val userFile = rootPath.child("permission_user.yaml")
        val good = userFile.readString()

        val first = newPlayer()
        val second = newPlayer()
        val firstUuid = first.first.uuid()
        val secondUuid = second.first.uuid()
        val takenName = first.second.name
        val neverJoined = "uuid-never-joined"

        // Offline is the branch under test; the online one writes the entity and never goes near
        // the database.
        leavePlayer(first.first)
        leavePlayer(second.first)

        try {
            userFile.writeString(
                """
                $firstUuid:
                    group: admin
                $secondUuid:
                    group: admin
                    name: "$takenName"
                $neverJoined:
                    group: admin
                """.trimIndent(),
                false
            )

            Permission.load()

            assertTrue(
                waitUntil(10000) { runBlocking { getPlayerData(firstUuid)?.permission } == "admin" },
                "an entry that can be written must land even though another entry in the same file cannot: the second entry's name collides with this player's, on the unique index over PlayerTable.name"
            )
            assertNull(
                runBlocking { getPlayerData(neverJoined) },
                "control: the third entry names a uuid with no row, which is the case the zero-rows-changed report exists for"
            )
        } finally {
            userFile.writeString(good, false)
            Permission.load()
        }
    }
}
