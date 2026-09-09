package essential.core

import PluginTest.Companion.createPlayer
import PluginTest.Companion.loadGame
import essential.common.database.data.createPlayerData
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * task-067: entityId used to be `val entityId = playerNumber`, reading a plain, unsynchronised Int
 * at PlayerData construction time. The increment that made the next construction see a different
 * value ran later, in a different file - attachPlayerData's `playerNumber++`, on the game thread,
 * after the object was already added to `players`. So two PlayerData objects constructed back to
 * back, before either had gone through attachPlayerData, both read the same un-incremented value
 * and got the same entityId. '#<id>' lookups (including /votekick) then resolved to whichever of
 * the two collided players sorted first, silently.
 *
 * playerNumber is now an AtomicInteger and entityId reads it via getAndIncrement() directly in the
 * PlayerData constructor - the id is handed out as the single atomic step that used to be two.
 * Constructing two PlayerData with nothing in between (never touching attachPlayerData, exactly the
 * window the finding described) must never produce the same id.
 */
class EntityIdSequenceTest {
    @BeforeTest
    fun setup() {
        loadGame(true)
    }

    @Test
    fun twoPlayersConstructedBackToBackNeverShareAnEntityId() = runBlocking {
        // createPlayerData is what the join coroutine calls to build the row; deliberately not
        // going anywhere near attachPlayerData, which is where the old increment used to live.
        val first = createPlayerData(createPlayer())
        val second = createPlayerData(createPlayer())

        assertNotEquals(
            first.entityId,
            second.entityId,
            "two players constructed before either was attached must not collide on the same " +
                    "entityId - this is the exact window task-067 reported"
        )
    }
}
