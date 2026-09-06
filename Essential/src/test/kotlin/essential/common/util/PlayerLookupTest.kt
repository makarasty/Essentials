package essential.common.util

import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import essential.common.database.data.PlayerData
import essential.common.database.table.PlayerTable
import kotlinx.coroutines.runBlocking
import mindustry.gen.Player
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PlayerLookupTest {
    companion object {
        private var done = false
    }

    private val joined = mutableListOf<Player>()

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    @AfterTest
    fun cleanup() {
        joined.forEach { leavePlayer(it) }
        joined.clear()
    }

    private fun join(name: String): Pair<Player, PlayerData> {
        val player = createPlayer()
        player.name(name)
        val data = joinPlayer(player)
        joined.add(player)
        return player to data
    }

    private fun <T> found(result: PlayerLookup.Result<T>): T {
        val hit = result as? PlayerLookup.Result.Found<T> ?: fail("expected Found but was $result")
        return hit.value
    }

    private fun ambiguous(result: PlayerLookup.Result<*>): PlayerLookup.Result.Ambiguous {
        return result as? PlayerLookup.Result.Ambiguous ?: fail("expected Ambiguous but was $result")
    }

    @Test
    fun lookup_exactNameBeatsPrefixAndSubstring() {
        val max = join("max")
        val max1 = join("max1")
        val maxim = join("maxim")

        assertEquals(max.first.uuid(), found(PlayerLookup.findOnline("max")).uuid())
        assertEquals(max1.first.uuid(), found(PlayerLookup.findOnline("max1")).uuid())
        assertEquals(maxim.first.uuid(), found(PlayerLookup.findOnline("maxi")).uuid())
        assertEquals(maxim.first.uuid(), found(PlayerLookup.findOnline("axim")).uuid())

        val result = ambiguous(PlayerLookup.findOnline("ax"))
        assertTrue(result.candidates.size >= 3, "expected at least three candidates but was ${result.candidates}")
        listOf(max, max1, maxim).forEach { (_, data) ->
            assertTrue(
                result.candidates.any { it == "[${data.entityId}] ${data.name}" },
                "candidate list ${result.candidates} should contain [${data.entityId}] ${data.name}"
            )
        }
    }

    @Test
    fun lookup_sessionId() {
        val (player, data) = join("session-id-target")

        assertEquals(player.uuid(), found(PlayerLookup.findOnline("#${data.entityId}")).uuid())
        assertEquals(player.uuid(), found(PlayerLookup.findOnline("${data.entityId}")).uuid())
        assertTrue(PlayerLookup.findOnline("#999999") is PlayerLookup.Result.NotFound)
    }

    @Test
    fun lookup_digitNameIsOnlyAnIdWhenOnline() {
        val (player, _) = join("987654")

        assertEquals(player.uuid(), found(PlayerLookup.findOnline("987654")).uuid())
        assertTrue(PlayerLookup.findOnline("#987654") is PlayerLookup.Result.NotFound)
    }

    @Test
    fun lookup_uuidAndColoredQuery() {
        val (player, _) = join("[red]Colorful[]")

        assertEquals(player.uuid(), found(PlayerLookup.findOnline(player.uuid())).uuid())
        assertEquals(player.uuid(), found(PlayerLookup.findOnline("[green]colorful[]")).uuid())
        assertEquals(player.uuid(), found(PlayerLookup.findOnline("  Colorful  ")).uuid())
    }

    @Test
    fun lookup_emojiNameNeedsTheId() {
        val (player, data) = join("🐟🐠🐡")

        assertTrue(PlayerLookup.findOnline("fish") is PlayerLookup.Result.NotFound)
        assertEquals(player.uuid(), found(PlayerLookup.findOnline("#${data.entityId}")).uuid())
    }

    @Test
    fun lookup_offlineNamesFromDatabase() {
        runBlocking {
            suspendTransaction {
                PlayerTable.insert {
                    it[name] = "offlinecandidate_one"
                    it[uuid] = "offline-lookup-one"
                }
                PlayerTable.insert {
                    it[name] = "offlinecandidate_two"
                    it[uuid] = "offline-lookup-two"
                }
            }

            try {
                assertEquals("offlinecandidate_one", found(PlayerLookup.findOffline("offlinecandidate_one")).name)
                assertEquals("offlinecandidate_two", found(PlayerLookup.findOffline("offline-lookup-two")).name)

                val result = ambiguous(PlayerLookup.findOffline("offlinecandidate"))
                assertEquals(2, result.candidates.size)
                assertTrue(
                    result.candidates.any { it.startsWith("offlinecandidate_one (") },
                    "candidate list ${result.candidates} should show the name and the last login date"
                )

                assertTrue(PlayerLookup.findOffline("offlinecandidate_three") is PlayerLookup.Result.NotFound)
            } finally {
                suspendTransaction {
                    PlayerTable.deleteWhere {
                        (PlayerTable.uuid eq "offline-lookup-one") or (PlayerTable.uuid eq "offline-lookup-two")
                    }
                }
            }
        }
    }
}
