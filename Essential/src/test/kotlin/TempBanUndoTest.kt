import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.serverCommand
import PluginTest.Companion.waitUntil
import essential.core.Undo
import mindustry.Vars
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-142: banPlayerID returns false when the target is already banned and did nothing. Before this
 * fix, applyTempBan ignored that and always pushed a fresh "tempban" undo entry whose revert is a full
 * Undo.unban — so undoing a re-tempban of an already-banned player would lift a ban that predates the
 * re-tempban entirely, rather than merely undoing the expiry change this command made.
 */
class TempBanUndoTest {
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
    fun reTempBanningAnAlreadyBannedPlayerDoesNotPushASecondUndoEntry() {
        val target = newPlayer()
        val uuid = target.first.uuid()
        val admins = Vars.netServer.admins

        serverCommand.handleMessage("tempban $uuid 10 first reason")
        assertEquals(
            true,
            waitUntil(10000) { admins.isIDBanned(uuid) },
            "the first tempban should create the ban this test is about"
        )
        assertEquals(
            1,
            Undo.stack(Undo.CONSOLE).count { it.targetUuid == uuid },
            "the first tempban should record exactly one undo entry"
        )

        // banPlayerID now returns false: the target is already banned, so nothing about the ban list
        // changed here beyond the expiry.
        serverCommand.handleMessage("tempban $uuid 20 second reason")

        assertEquals(
            1,
            Undo.stack(Undo.CONSOLE).count { it.targetUuid == uuid },
            "re-tempbanning an already-banned player must not push a second undo entry, because " +
                "reverting it would fully unban a player who was already banned before this command ran"
        )

        admins.unbanPlayerID(uuid)
    }
}
