import PluginTest.Companion.clientCommand
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import PluginTest.Companion.waitUntil
import essential.common.database.WorldHistoryBuffer
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Team
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-140: rollback used to select whose history to undo with
 * `it.player.contains(arg[0], ignoreCase = true)` - a case-insensitive substring match against a
 * player-chosen display name, stored on TileLog with no uuid to disambiguate. "Bobby" matched a
 * rollback aimed at "Bob", reverting a bystander's tiles.
 *
 * The honest fix needs a uuid on TileLog / WorldHistoryTable, which lives in CoreEvent.kt and is not
 * this cluster's file (filed as an ask). What is fixed here, in Commands.kt alone, is the substring
 * itself: matching the exact stored name instead of a substring of it closes the "Bobby is not Bob"
 * case and the "renamed to contain someone else's name" case. It does not close two entries sharing
 * one exact name after a later rename - that residue needs the uuid.
 */
class RollbackPlayerMatchTest {
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
    fun rollbackMatchesTheExactNameNotAnyNameThatContainsIt() {
        val nonce = Random.nextInt(100000, 999999)
        // bobbyName must actually contain bobName as a substring, the way real "Bobby" contains "Bob" -
        // the nonce goes inside bobName's own text, not after it, or "contains" would never be true and
        // this test would pass for the wrong reason regardless of which match the command uses.
        val bobName = "Bob$nonce"
        val bobbyName = "${bobName}by"
        val bobX: Short = 30
        val bobY: Short = 33
        val bobbyX: Short = 36
        val bobbyY: Short = 39

        // Both tiles start on a block the history disagrees with, so the test can tell "rollback
        // touched this tile" apart from "rollback left it alone" instead of both ending on air.
        Vars.world.tile(bobX.toInt(), bobY.toInt())?.setBlock(Blocks.titaniumWall, Team.sharded, 0)
        Vars.world.tile(bobbyX.toInt(), bobbyY.toInt())?.setBlock(Blocks.titaniumWall, Team.sharded, 0)

        WorldHistoryBuffer.enqueue(
            time = 1000, player = bobName, action = "place",
            x = bobX, y = bobY, tile = Blocks.copperWall.name, rotate = 0, team = "sharded", value = null
        )
        WorldHistoryBuffer.enqueue(
            time = 1000, player = bobbyName, action = "place",
            x = bobbyX, y = bobbyY, tile = Blocks.copperWall.name, rotate = 0, team = "sharded", value = null
        )

        val admin = newPlayer()
        try {
            setPermission(admin.first, "owner", true)

            clientCommand.handleMessage("/rollback $bobName", admin.first)

            assertEquals(
                true,
                waitUntil(10000) { Vars.world.tile(bobX.toInt(), bobY.toInt())?.block() != Blocks.titaniumWall },
                "rollback should have reverted Bob's own tile"
            )
            assertEquals(
                Blocks.titaniumWall,
                Vars.world.tile(bobbyX.toInt(), bobbyY.toInt())?.block(),
                "rollback Bob must not also revert Bobby's tile just because the name contains \"Bob\""
            )
        } finally {
            leavePlayer(admin.first)
            // These coordinates started on air, not on a leftover from an earlier test class; restore
            // that rather than leaving titanium-wall behind for whatever runs after this class.
            Vars.world.tile(bobX.toInt(), bobY.toInt())?.setBlock(Blocks.air, Team.derelict, 0)
            Vars.world.tile(bobbyX.toInt(), bobbyY.toInt())?.setBlock(Blocks.air, Team.derelict, 0)
        }
    }

    @Test
    fun rollbackMatchesAColoredNameAgainstThePlainNameTheAdminTyped() {
        val nonce = Random.nextInt(100000, 999999)
        // "place"/"break" store the acting player's raw name (CoreEvent.kt's TileLog construction uses
        // target.name, not plainName()), and a client can set color markup on its own name, a permission
        // group can recolor it (Permission.kt), or /color can rewrite it every tick (Trigger.kt) - none
        // of that is under the admin's control when they type a plain name at the rollback prompt.
        val plainName = "Colorful$nonce"
        val coloredName = "[red]$plainName[]"
        val x: Short = 42
        val y: Short = 45

        Vars.world.tile(x.toInt(), y.toInt())?.setBlock(Blocks.titaniumWall, Team.sharded, 0)

        WorldHistoryBuffer.enqueue(
            time = 1000, player = coloredName, action = "place",
            x = x, y = y, tile = Blocks.copperWall.name, rotate = 0, team = "sharded", value = null
        )

        val admin = newPlayer()
        try {
            setPermission(admin.first, "owner", true)

            clientCommand.handleMessage("/rollback $plainName", admin.first)

            assertEquals(
                true,
                waitUntil(10000) { Vars.world.tile(x.toInt(), y.toInt())?.block() != Blocks.titaniumWall },
                "rollback typed with the plain name must still match a colored stored name, not silently do nothing"
            )
        } finally {
            leavePlayer(admin.first)
            Vars.world.tile(x.toInt(), y.toInt())?.setBlock(Blocks.air, Team.derelict, 0)
        }
    }
}
