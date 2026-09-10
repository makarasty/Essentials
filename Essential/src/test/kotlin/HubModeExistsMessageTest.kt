import PluginTest.Companion.clientCommand
import PluginTest.Companion.err
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.observeMessages
import PluginTest.Companion.setPermission
import essential.common.pluginData
import mindustry.Vars
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * task-124 (ask/9b-1.md): hub mode's on/off state is one value shared by every server in the cluster,
 * so a server whose map differs from the one that already holds it gets refused. The refusal used to
 * name nothing, leaving an admin unable to tell which map holds it or that another server set it at
 * all. Not a fix for the underlying shared row - that needs a per-server identity to land somewhere,
 * filed as the ask - but the refusal can at least say what it refused against.
 */
class HubModeExistsMessageTest {
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
    fun hubSetRefusalNamesTheMapThatAlreadyHoldsIt() {
        val (player, playerData) = newPlayer()
        val previous = pluginData.hubMapName
        try {
            setPermission(player, "owner", true)
            pluginData.hubMapName = "a-different-map-${Vars.state.map.name()}"

            val expected = err("command.hub.mode.exists.at", pluginData.hubMapName!!)
            clientCommand.handleMessage("/hub set", player)

            // /hub's "set" branch runs inside scope.launch, so the message is not necessarily there the
            // instant handleMessage returns - and PlayerData keeps only the newest message it was sent,
            // so polling lastReceivedMessage directly would lose to anything landing behind the reply.
            // observeMessages accumulates every message sent in the window instead.
            val seen = observeMessages(playerData, 5000) { it == expected }
            assertTrue(expected in seen, "the refusal should name the map hub mode is already held for, saw: $seen")
        } finally {
            pluginData.hubMapName = previous
            leavePlayer(player)
        }
    }
}
