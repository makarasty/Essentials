package essential.core

import PluginTest.Companion.loadGame
import essential.common.permission.Permission
import essential.common.rootPath
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionLoadTest {
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

    /** Runs [Permission.load] off the test thread so a loader that never returns fails instead of hanging the suite. */
    private fun loadWithDeadline(): Throwable? {
        var failure: Throwable? = null
        val worker = Thread {
            try {
                Permission.load()
            } catch (e: Throwable) {
                failure = e
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(15_000)

        assertFalse(worker.isAlive, "load() never returned; the inheritance walk is looping")
        return failure
    }

    @Test
    fun permission_inheritanceCycleDoesNotHangLoad() {
        val mainFile = rootPath.child("permission.yaml")
        val good = mainFile.readString()

        try {
            mainFile.writeString(
                """
                builder:
                    inheritance: veteran
                    permission:
                        - build
                veteran:
                    inheritance: builder
                    permission:
                        - vote
                loner:
                    inheritance: loner
                    permission:
                        - help
                visitor:
                    default: true
                    permission:
                        - login
                """.trimIndent(),
                false
            )

            val failure = loadWithDeadline()

            assertNull(failure, "a circular inheritance chain should be cut, not thrown out of load(): $failure")
            assertTrue(Permission.hasGroup("builder"), "the roles either side of the cycle should still be loaded")
            assertTrue(Permission.hasGroup("loner"), "a role that inherits from itself should still be loaded")
        } finally {
            mainFile.writeString(good, false)
            Permission.load()
        }
    }

    @Test
    fun permission_entryWithoutGroupGetsTheDefaultGroup() {
        val mainFile = rootPath.child("permission.yaml")
        val userFile = rootPath.child("permission_user.yaml")
        val goodMain = mainFile.readString()
        val goodUser = userFile.readString()
        val uuid = "uuid-without-a-group"

        try {
            mainFile.writeString(
                """
                user:
                    permission:
                        - rtv
                        - votemap
                        - tp
                visitor:
                    default: true
                    permission:
                        - help
                        - login
                        - reg
                """.trimIndent(),
                false
            )
            userFile.writeString("$uuid:\n    name: Bob\n", false)

            Permission.load()

            assertEquals(
                "visitor",
                Permission.groupOf(uuid, "user"),
                "an entry with no group: key belongs to the group permission.yaml marks default, not to user"
            )
        } finally {
            mainFile.writeString(goodMain, false)
            userFile.writeString(goodUser, false)
            Permission.load()
        }
    }
}
