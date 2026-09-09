package essential.core

import PluginTest.Companion.clientCommand
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import PluginTest.Companion.setPermission
import mindustry.Vars
import mindustry.maps.Map
import mindustry.maps.Maps
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-139: the vote tally at the bottom of nextMap runs unconditionally after every /nextmap call,
 * including an admin's own. So an admin's explicit override, set two lines above via
 * Vars.maps.setNextMapOverride(target), was immediately recomputed and silently replaced by whichever
 * map currently had the most votes.
 */
class NextMapVoteTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        mapVotes.clear()
    }

    /** nextMapOverride has no getter (getNextMap both reads and clears it), so read the field directly. */
    private fun currentOverride(): Map? {
        val field = Maps::class.java.getDeclaredField("nextMapOverride")
        field.isAccessible = true
        return field.get(Vars.maps) as Map?
    }

    @Test
    fun anAdminsExplicitNextMapChoiceIsNotOverwrittenByTheVoteTally() {
        val firstVoter = newPlayer()
        clientCommand.handleMessage("/nextmap Glacier", firstVoter.first)

        val secondVoter = newPlayer()
        clientCommand.handleMessage("/nextmap Glacier", secondVoter.first)

        // Glacier now leads 2-0. An admin then casts the deciding vote for a different map, expecting
        // their nextmap.admin override to be the final word.
        val admin = newPlayer()
        setPermission(admin.first, "admin", true)
        clientCommand.handleMessage("/nextmap Fork", admin.first)

        assertEquals(
            "Fork",
            currentOverride()?.plainName(),
            "the admin's explicit override must stand, not the map with the most votes"
        )
    }

    @Test
    fun aLaterNonAdminVoteDoesNotClobberTheStandingAdminOverride() {
        val admin = newPlayer()
        setPermission(admin.first, "admin", true)
        clientCommand.handleMessage("/nextmap Fork", admin.first)

        // Two more players vote for a different map after the admin's override is already in place -
        // the popularity tally these votes trigger must keep re-affirming Fork, not recompute over it.
        val firstVoter = newPlayer()
        clientCommand.handleMessage("/nextmap Glacier", firstVoter.first)
        val secondVoter = newPlayer()
        clientCommand.handleMessage("/nextmap Glacier", secondVoter.first)

        assertEquals(
            "Fork",
            currentOverride()?.plainName(),
            "votes cast after the admin's override must not silently replace it"
        )
    }
}
