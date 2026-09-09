package essential.core

import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import essential.common.permission.Permission
import essential.common.rootPath
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a role picks up from the role it inherits.
 *
 * The wildcard `all` is deliberately not copied down a chain. The test that skipped it was a
 * substring test, so it also skipped every node with those three letters inside it - `killall` and
 * `kickall` are both real commands here - and the role simply ended up without them, which is
 * indistinguishable from a permission the operator never granted.
 */
class PermissionInheritanceTest {
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
    fun permission_inheritanceKeepsANodeThatMerelyContainsAll() {
        val mainFile = rootPath.child("permission.yaml")
        val userFile = rootPath.child("permission_user.yaml")
        val good = mainFile.readString()
        // setperm reaches writeUser, which only leaves the file alone because the edit changes
        // nothing. Restoring it anyway rather than depending on that.
        val goodUser = userFile.readString()

        try {
            mainFile.writeString(
                """
                owner:
                    admin: true
                    permission:
                        - all
                admin:
                    inheritance: user
                    permission:
                        - kick.admin
                user:
                    permission:
                        - all
                        - killall
                        - rtv
                visitor:
                    default: true
                    permission:
                        - help
                """.trimIndent(),
                false
            )
            Permission.load()

            val target = newPlayer()
            setPermission(target.first, "admin", false)

            assertTrue(
                Permission.check(target.second, "rtv"),
                "control: an ordinary node from the inherited role must arrive. If this one fails, setperm did not put the player in the admin group and nothing below means anything"
            )
            assertTrue(
                Permission.check(target.second, "killall"),
                "killall is a node of its own and must be inherited; only the wildcard 'all' is skipped"
            )
            // Not a regression guard - this passes either way - but it fails if somebody "fixes"
            // the substring test by deleting the wildcard skip altogether.
            assertFalse(
                Permission.check(target.second, "all"),
                "and the wildcard itself must still not be copied down the chain"
            )
        } finally {
            mainFile.writeString(good, false)
            userFile.writeString(goodUser, false)
            Permission.load()
        }
    }
}
