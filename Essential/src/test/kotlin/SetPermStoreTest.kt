import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.log
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.data.PlayerData
import essential.common.database.data.getPlayerData
import essential.common.permission.Permission
import essential.common.rootPath
import kotlinx.coroutines.runBlocking
import mindustry.gen.Player
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Six servers share one database but each keeps its own permission_user.yaml, and the file wins over the
 * shared column. A group /setperm writes into the local file is therefore a group the other five servers
 * never see, and one this server pushes back over theirs on its next load. These tests hold the line that
 * /setperm leaves the shared column as the only place it wrote, while an entry the operator wrote by hand
 * is still honoured and kept in step.
 */
class SetPermStoreTest {
    companion object {
        private var done = false
    }

    private var restore: String? = null

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    @AfterTest
    fun cleanup() {
        restore?.let {
            rootPath.child("permission_user.yaml").writeString(it, false)
            Permission.load()
        }
        restore = null
    }

    private fun admin(): Pair<Player, PlayerData> = newPlayer().also { setPermission(it.first, "owner", true) }

    @Test
    fun setPerm_leavesNothingLocalToMaskTheSharedGroup() {
        val (admin, _) = admin()
        val (target, targetData) = newPlayer()
        val uuid = target.uuid()

        clientCommand.handleMessage("/setperm ${target.name} admin", admin)

        assertEquals("admin", targetData.permission, "the group should apply on this server")
        assertFalse(Permission.hasUserEntry(uuid), "no local entry should have been written")
        assertTrue(
            waitUntil(10000) { runBlocking { getPlayerData(uuid)?.permission } == "admin" },
            "the shared row is the only place the group was written, so it has to reach it"
        )

        // What a restart does: the file is re-read and every entry in it is pushed back over the shared
        // row. With no entry for this player there is nothing to push, and the group survives.
        Permission.load()
        assertFalse(Permission.hasUserEntry(uuid), "a reload must not resurrect a local entry")
        assertEquals("admin", Permission.groupOf(uuid, targetData.permission), "the group must survive a reload")

        leavePlayer(target)
        leavePlayer(admin)
    }

    @Test
    fun setPerm_keepsAnEntryTheOperatorWroteByHand() {
        val (admin, adminData) = admin()
        val (target, _) = newPlayer()
        val uuid = target.uuid()
        val file = rootPath.child("permission_user.yaml")
        restore = file.readString()

        file.writeString("$uuid:\n    group: \"user\"\n    customField: \"keep me\"\n", false)
        Permission.load()

        clientCommand.handleMessage("/setperm ${target.name} admin", admin)

        assertEquals(
            log("command.setPerm.success", target.name, "admin"),
            adminData.lastReceivedMessage,
            "the command should have applied the group"
        )
        assertTrue(Permission.hasUserEntry(uuid), "an entry the operator wrote must survive")
        assertEquals("admin", Permission.groupOf(uuid, "visitor"), "and must be moved to the new group")
        assertTrue(file.readString().contains("keep me"), "the rest of the entry must survive too")

        leavePlayer(target)
        leavePlayer(admin)
    }
}
