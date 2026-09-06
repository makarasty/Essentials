package essential.common.util

import PluginTest.Companion.createPlayer
import PluginTest.Companion.joinPlayer
import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import essential.common.database.data.PlayerData
import essential.common.database.data.createTemporaryPlayerData
import essential.common.database.table.PlayerTable
import essential.common.players
import kotlinx.coroutines.runBlocking
import mindustry.gen.Groups
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
import kotlin.test.assertNull
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
        joined.forEach { player ->
            runCatching { leavePlayer(player) }
            player.remove()
        }
        joined.clear()
        Groups.player.update()
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
                    result.candidates.any { it == "offlinecandidate_one (offline-)" },
                    "candidate list ${result.candidates} should show the name and the uuid prefix"
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

    private fun withRows(vararg rows: Pair<String, String>, body: suspend () -> Unit) {
        runBlocking {
            suspendTransaction {
                rows.forEach { (rowName, rowUuid) ->
                    PlayerTable.insert {
                        it[name] = rowName
                        it[uuid] = rowUuid
                    }
                }
            }
            try {
                body()
            } finally {
                suspendTransaction {
                    rows.forEach { (_, rowUuid) -> PlayerTable.deleteWhere { PlayerTable.uuid eq rowUuid } }
                }
            }
        }
    }

    @Test
    fun lookup_exactOfflineNameBeatsOnlineFuzzyMatch() {
        join("qlxmaxwell")

        withRows("qlxmax" to "qlx-exact-offline") {
            assertEquals("qlx-exact-offline", found(PlayerLookup.findOffline("qlxmax")).uuid)
            assertEquals("qlxmaxwell", found(PlayerLookup.findOffline("qlxmaxw")).name)
        }
    }

    @Test
    fun lookup_offlineColoredNameIsFoundByPlainQuery() {
        withRows("Q[red]lxcolor[]" to "qlx-color-offline") {
            assertEquals("qlx-color-offline", found(PlayerLookup.findOffline("qlxcolor")).uuid)
        }
    }

    @Test
    fun lookup_ambiguousOfflineShowsUuidAndKeepsFullNames() {
        val long = "qlxambiguous_first_candidate_with_a_very_long_name"

        withRows(long to "qlx-ambiguous-one", "qlxambiguous_second" to "qlx-ambiguous-two") {
            val result = ambiguous(PlayerLookup.findOffline("qlxambiguous"))

            assertTrue(result.offline, "offline candidates should be flagged as offline")
            assertTrue(
                result.candidates.contains("$long (qlx-ambi)"),
                "candidate list ${result.candidates} should keep the full name and show the uuid prefix"
            )
            assertTrue(
                result.candidates.contains("qlxambiguous_second (qlx-ambi)"),
                "candidate list ${result.candidates} should contain the second account"
            )
        }
    }

    @Test
    fun lookup_exactRefusesPrefixAndSubstring() {
        withRows("qlxexactonly" to "qlx-exact-only") {
            assertTrue(PlayerLookup.findExact("qlxexact") is PlayerLookup.Result.NotFound)
            assertTrue(PlayerLookup.findExact("xactonl") is PlayerLookup.Result.NotFound)
            assertEquals("qlx-exact-only", found(PlayerLookup.findExact("qlxexactonly")).uuid)
            assertEquals("qlx-exact-only", found(PlayerLookup.findExact("qlx-exact-only")).uuid)
        }
    }

    @Test
    fun lookup_onlineTemporaryDataIsNotRegistered() {
        val player = createPlayer()
        player.name("qlxtemporary")
        val data = createTemporaryPlayerData(player)
        data.temporary = true
        players.add(data)

        try {
            assertEquals(player.uuid(), found(PlayerLookup.findOnline("qlxtemporary")).uuid())
            assertNull(PlayerLookup.onlineData("qlxtemporary"))
            runBlocking {
                assertTrue(found(PlayerLookup.findOffline("qlxtemporary")).temporary)
                assertNull(PlayerLookup.offline("qlxtemporary"))
            }
        } finally {
            players.remove(data)
            player.remove()
            Groups.player.update()
        }
    }
}
