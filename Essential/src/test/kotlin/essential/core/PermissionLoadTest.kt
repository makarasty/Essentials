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

    @Test
    fun permission_reloadKeepsTheAccountServiceDefault() {
        val mainFile = rootPath.child("permission.yaml")
        val userFile = rootPath.child("permission_user.yaml")
        val goodMain = mainFile.readString()
        val goodUser = userFile.readString()
        val uuid = "uuid-reloaded-without-a-group"

        try {
            mainFile.writeString(
                """
                user:
                    permission:
                        - rtv
                visitor:
                    default: true
                    permission:
                        - login
                """.trimIndent(),
                false
            )
            userFile.writeString("$uuid:\n    name: Bob\n", false)

            // What ProtectService.init does once at boot when account authentication is configured.
            Permission.setAuthDefault("user")
            Permission.load()

            assertEquals(
                "user",
                Permission.default,
                "a reload must not answer for the account service with the permission.yaml default"
            )
            assertEquals(
                "visitor",
                Permission.groupOf(uuid, "owner"),
                "and it must not answer for permission.yaml with the account service default either: an entry with no group: key still belongs to the role permission.yaml marks default"
            )
        } finally {
            Permission.setAuthDefault(null)
            mainFile.writeString(goodMain, false)
            userFile.writeString(goodUser, false)
            Permission.load()
        }
    }


    @Test
    fun permission_aUserFileEditThatChangesNothingKeepsTheFileAndItsBackup() {
        val userFile = rootPath.child("permission_user.yaml")
        val backupFile = rootPath.child("permission_user.yaml.bak")
        val goodUser = userFile.readString()
        val uuid = "uuid-with-no-entry"

        try {
            userFile.writeString("# an operator comment, and an entry for nobody\n---\n", false)
            Permission.load()
            if (backupFile.exists()) backupFile.delete()
            val before = userFile.readString()

            // What /setperm does for a player the file carries no entry for: it wants the live effect
            // and nothing else, and there is no other way to ask for it.
            Permission.removeUserEntry(uuid, "visitor")

            assertFalse(backupFile.exists(), "an edit that removes nothing must not overwrite the backup")
            assertEquals(before, userFile.readString(), "and must leave the operator's own comments alone")
        } finally {
            if (backupFile.exists()) backupFile.delete()
            userFile.writeString(goodUser, false)
            Permission.load()
        }
    }
}
