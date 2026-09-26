package essential.core

import PluginTest.Companion.expectingErrors
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.waitUntil
import arc.util.Log
import essential.common.database.data.getPlayerData
import essential.common.permission.Permission
import essential.common.rootPath
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
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
 * show you: a single entry whose row update fails for any reason rolls the whole file back and
 * every other entry is silently lost.
 *
 * So the file is applied a few entries at a time, each in its own transaction, and this pins the
 * property that costs.
 *
 * The entry that fails here carries a `name:` longer than PlayerTable.name's 256 characters, which
 * Exposed refuses inside the transaction before anything is sent, whatever the database. It used to
 * be a name colliding with another row's, until players.name stopped being unique and that entry
 * quietly started succeeding - with every assertion below still green. Hence the first assertion,
 * which checks the failure actually happened.
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
        val previousLogger = Log.logger
        // Written from the coroutine applyOffline runs on, read from this thread.
        val lines = CopyOnWriteArrayList<String>()

        val first = newPlayer()
        val second = newPlayer()
        val firstUuid = first.first.uuid()
        val secondUuid = second.first.uuid()
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
                    name: "${"x".repeat(257)}"
                $neverJoined:
                    group: admin
                """.trimIndent(),
                false
            )

            // Recorded before delegating: the handler the run holds is the error guard, and it throws.
            Log.logger = Log.LogHandler { level, text ->
                lines.add(text)
                previousLogger.log(level, text)
            }

            val failure = "entry for $secondUuid could not be written"
            expectingErrors(failure) {
                Permission.load()

                assertTrue(
                    waitUntil(10000) { lines.any { it.contains(failure) } },
                    "precondition: the second entry's write must fail, or this test proves nothing. If " +
                        "PlayerTable.name was widened, give it some other value the update rejects. The log had: $lines"
                )
            }

            assertTrue(
                waitUntil(10000) { runBlocking { getPlayerData(firstUuid)?.permission } == "admin" },
                "an entry that can be written must land even though another entry in the same file cannot"
            )
            assertNull(
                runBlocking { getPlayerData(neverJoined) },
                "control: the third entry names a uuid with no row, which is the case the zero-rows-changed report exists for"
            )
        } finally {
            Log.logger = previousLogger
            userFile.writeString(good, false)
            Permission.load()
        }
    }
}
