import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.WorldHistoryBuffer
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Items
import mindustry.world.blocks.distribution.Sorter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-141: rollback restored a block's config by handing Building.configure the flattened string
 * straight from history. That works by accident for a String-typed config (a message block) and is a
 * silent no-op for anything else, because Building.configured only dispatches to a handler registered
 * under the value's own runtime class (Block.configurations) - a String is never that class for a
 * content-typed config such as a sorter's item filter.
 */
class RollbackConfigTest {
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
    fun rollbackReconstructsAContentTypedConfigInsteadOfHandingItARawString() {
        val x: Short = 40
        val y: Short = 45
        val admin = newPlayer()
        setPermission(admin.first, "owner", true)
        val target = newPlayer()

        // Someone placed a sorter and set it to filter copper before the target ever touched it.
        WorldHistoryBuffer.enqueue(
            time = 1000, player = admin.first.name(), action = "place",
            x = x, y = y, tile = Blocks.sorter.name, rotate = 0, team = "sharded", value = null
        )
        WorldHistoryBuffer.enqueue(
            time = 2000, player = admin.first.name(), action = "config",
            x = x, y = y, tile = Blocks.sorter.name, rotate = 0, team = "sharded", value = Items.copper.name
        )
        // The target's own action is what makes this tile part of their rollback, and it changes the
        // filter to something else - rollback must restore the copper filter above, not this one.
        WorldHistoryBuffer.enqueue(
            time = 3000, player = target.first.name(), action = "config",
            x = x, y = y, tile = Blocks.sorter.name, rotate = 0, team = "sharded", value = Items.titanium.name
        )

        clientCommand.handleMessage("/rollback ${target.first.name()}", admin.first)

        assertEquals(
            true,
            waitUntil(10000) { Vars.world.tile(x.toInt(), y.toInt())?.block() == Blocks.sorter },
            "the sorter itself should have been restored"
        )
        val build = Vars.world.tile(x.toInt(), y.toInt())?.build as? Sorter.SorterBuild
        assertEquals(
            Items.copper,
            build?.sortItem,
            "the filter must be reconstructed as the Item it was, not left unset by handing " +
                "Building.configure the raw string"
        )
    }
}
