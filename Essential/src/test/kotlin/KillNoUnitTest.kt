import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.serverCommand
import PluginTest.Companion.setPermission
import essential.common.database.data.PlayerData
import mindustry.gen.Player
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-076: both /kill variants dereferenced the target's unit with no null check
 * (other.unit().kill()), so killing a player who is dead, respawning, or spectating threw a
 * NullPointerException instead of reporting anything. The self-kill branch two lines above already
 * used the safe call, so the omission was local to the "kill someone else" path.
 */
class KillNoUnitTest {
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

    private fun admin(): Pair<Player, PlayerData> = newPlayer().also { setPermission(it.first, "owner", true) }

    @Test
    fun clientKill_targetWithNoUnitReportsAnErrorInsteadOfThrowing() {
        val (admin, adminData) = admin()
        val (target, _) = newPlayer()
        // clearUnit() only detaches the player's reference - the entity itself keeps existing in
        // Groups.unit, orphaned, until removed by hand. Captured before clearing so it can be cleaned
        // up afterward, since leavePlayer's own cleanup reads player.unit(), which is now null.
        val orphan = target.unit()
        try {
            target.clearUnit()

            clientCommand.handleMessage("/kill ${target.name}", admin)

            assertEquals(err("command.kill.no.unit", target.plainName()), adminData.lastReceivedMessage)
        } finally {
            orphan?.takeIf { it.isValid }?.remove()
            leavePlayer(admin)
            leavePlayer(target)
        }
    }

    @Test
    fun serverKill_targetWithNoUnitDoesNotThrow() {
        val (target, _) = newPlayer()
        val orphan = target.unit()
        try {
            target.clearUnit()

            // Must not throw. The server console variant has no player to report an error to, so this
            // only proves the command survives a target with no unit.
            serverCommand.handleMessage("kill ${target.name}")
        } finally {
            orphan?.takeIf { it.isValid }?.remove()
            leavePlayer(target)
        }
    }
}
