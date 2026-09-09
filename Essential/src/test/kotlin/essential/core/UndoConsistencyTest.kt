package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.waitUntil
import essential.common.permission.Permission
import essential.common.rootPath
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Undos that reported success while doing the wrong thing, and are silent on a running server.
 */
class UndoConsistencyTest {
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
    fun undo_permissionLeavesTheRowAloneWhenTheFileWriteIsRefused() {
        val userFile = rootPath.child("permission_user.yaml")
        val backupFile = rootPath.child("permission_user.yaml.bak")
        val good = userFile.readString()

        val target = newPlayer()
        val uuid = target.first.uuid()
        val before = target.second.permission

        try {
            // A tab in the indentation: YAML forbids it, so the file no longer parses and every write
            // through Permission is refused until the operator repairs it.
            userFile.writeString("$uuid:\n\tname: broken\n", false)
            Permission.load()
            assertNotNull(Permission.userFileProblem(), "the file must be seen as unparseable, or this test proves nothing")

            Undo.permission(uuid, "owner", false)

            assertFalse(
                waitUntil(3000) { target.second.permission == "owner" },
                "the row must not be moved while permission_user.yaml refuses the matching write: half an undo is worse than none, and once the file parses again it is the file that decides"
            )
            assertEquals(before, target.second.permission, "the group should be exactly what it was")
        } finally {
            userFile.writeString(good, false)
            if (backupFile.exists()) backupFile.delete()
            Permission.load()
        }
    }

}
    }
}
