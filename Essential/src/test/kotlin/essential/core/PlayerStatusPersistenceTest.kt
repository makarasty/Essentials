package essential.core

import PluginTest.Companion.leavePlayer
import PluginTest.Companion.loadGame
import PluginTest.Companion.newPlayer
import essential.common.database.data.getPlayerAchievements
import essential.common.database.data.getPlayerData
import essential.common.database.data.mergePlayerAccounts
import essential.common.database.table.AchievementTable
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PlayerStatusPersistenceTest {
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
    fun achievementCountersSurviveALeaveAndRejoin() {
        val (player, data) = newPlayer()
        try {
            // 90 minutes on Serpulo and seven map clears, the kind of progress that used to be lost.
            data.status["record.time.serpulo"] = "5400"
            data.status["record.map.clear.count"] = "7"

            runBlocking {
                assertTrue(data.update(), "The row must be written.")

                val reloaded = getPlayerData(player.uuid())
                    ?: fail("Player ${player.uuid()} disappeared from the database.")

                assertEquals(
                    "5400",
                    reloaded.status["record.time.serpulo"],
                    "Achievement progress must come back from the players row."
                )
                assertEquals("7", reloaded.status["record.map.clear.count"])
            }
        } finally {
            leavePlayer(player)
        }
    }

    @Test
    fun sessionOnlyFlagsAreNotCarriedIntoTheNextSession() {
        val (player, data) = newPlayer()
        try {
            // pendingLogin is the confirmation token for deleting the player's own row, and the hub
            // keys are a half-finished block selection. Neither may outlive the session that set it.
            data.status["pendingLogin"] = "someaccount"
            data.status["hub_block_selecting"] = "true"

            runBlocking {
                assertTrue(data.update(), "The row must be written.")

                val reloaded = getPlayerData(player.uuid())
                    ?: fail("Player ${player.uuid()} disappeared from the database.")

                assertNull(
                    reloaded.status["pendingLogin"],
                    "A confirmation that outlives its conversation is a confirmation nobody gave."
                )
                assertNull(reloaded.status["hub_block_selecting"])
            }
        } finally {
            leavePlayer(player)
        }
    }

    @Test
    fun mergingTwoAccountsAddsTheirAchievementProgress() {
        val (source, sourceData) = newPlayer()
        val (target, targetData) = newPlayer()
        try {
            sourceData.status["record.time.serpulo"] = "5400"
            sourceData.status["record.map.clear.count"] = "3"
            // Not running totals: a timestamp and three windows that get reset rather than accumulated.
            sourceData.status["record.turret.quill.kill.time"] = "2000"
            sourceData.status["record.pvp.win.streak.current"] = "3"
            sourceData.status["record.warp.disconnect.duration"] = "20"
            sourceData.status["record.time.noafk"] = "10000"
            // Burst counts, reset to 1 rather than zero when their ten-second window lapses, so they
            // read as totals and are not. Summed, they hand over QuillKiller at 5 and ZenithKiller at 30.
            sourceData.status["record.turret.quill.kill"] = "3"
            sourceData.status["record.turret.zenith.kill"] = "20"
            // Genuine running totals whose keys start with record.time. - the exclusion is on the
            // .time suffix, not on the word appearing anywhere in the key.
            sourceData.status["record.time.chat"] = "600"

            targetData.status["record.time.serpulo"] = "1800"
            targetData.status["record.map.clear.count"] = "4"
            targetData.status["record.turret.quill.kill.time"] = "1000"
            targetData.status["record.pvp.win.streak.current"] = "4"
            targetData.status["record.warp.disconnect.duration"] = "15"
            targetData.status["record.time.noafk"] = "5000"
            targetData.status["record.turret.quill.kill"] = "3"
            targetData.status["record.turret.zenith.kill"] = "15"
            targetData.status["record.time.chat"] = "400"

            runBlocking {
                assertTrue(sourceData.update())
                assertTrue(targetData.update())

                mergePlayerAccounts(source.uuid(), target.uuid())

                val merged = getPlayerData(target.uuid())
                    ?: fail("Target ${target.uuid()} disappeared from the database.")

                assertEquals(
                    "7200",
                    merged.status["record.time.serpulo"],
                    "Merging accounts sums their counters, the way it already sums the columns."
                )
                assertEquals("7", merged.status["record.map.clear.count"])

                // A .time key is a System.currentTimeMillis stamp: summed, it lands in the future and
                // the ten-second window reading it never closes again, awarding QuillKiller for good.
                assertEquals(
                    "1000",
                    merged.status["record.turret.quill.kill.time"],
                    "A timestamp is not a running total, so the target keeps its own."
                )
                // Windows are reset to zero rather than accumulated. Summing two half-runs awards a
                // whole one that never happened.
                assertEquals(
                    "4",
                    merged.status["record.pvp.win.streak.current"],
                    "A .current window is not a running total."
                )
                assertEquals(
                    "15",
                    merged.status["record.warp.disconnect.duration"],
                    "A .duration window is not a running total."
                )
                assertEquals(
                    "5000",
                    merged.status["record.time.noafk"],
                    "record.time.noafk is reset on every join, so it is a window too."
                )
                assertEquals(
                    "3",
                    merged.status["record.turret.quill.kill"],
                    "A burst count is not a running total: summed, two mid-burst accounts reach 5 " +
                            "and QuillKiller is awarded for a burst that never happened."
                )
                assertEquals(
                    "15",
                    merged.status["record.turret.zenith.kill"],
                    "Same for ZenithKiller at 30."
                )
                assertEquals(
                    "1000",
                    merged.status["record.time.chat"],
                    "record.time.chat is a running total: the exclusion is the .time suffix, not the " +
                            "word appearing anywhere in the key."
                )
            }
        } finally {
            leavePlayer(source)
            leavePlayer(target)
        }
    }

    @Test
    fun mergingTwoAccountsCarriesTheSourcesAchievements() {
        val (source, sourceData) = newPlayer()
        val (target, targetData) = newPlayer()
        try {
            val earlier = LocalDateTime(2024, 1, 1, 0, 0)
            val later = LocalDateTime(2025, 1, 1, 0, 0)

            runBlocking {
                assertTrue(sourceData.update())
                assertTrue(targetData.update())

                suspendTransaction {
                    listOf(
                        // Held by both, earned earlier on the source.
                        Triple(sourceData.id, "apm50", earlier),
                        Triple(targetData.id, "apm50", later),
                        // Held only by the source, and hidden: the reload loop skips hidden
                        // achievements, so this one has no way back once its row is gone.
                        Triple(sourceData.id, "newyear", earlier),
                        // Held only by the target.
                        Triple(targetData.id, "apm100", later)
                    ).forEach { (owner, name, at) ->
                        AchievementTable.insert {
                            it[playerId] = owner
                            it[achievementName] = name
                            it[completedAt] = at
                        }
                    }
                }

                mergePlayerAccounts(source.uuid(), target.uuid())

                val carried = getPlayerAchievements(targetData)
                    .associate { it.achievementName to it.completedAt }

                assertEquals(
                    earlier,
                    carried["newyear"],
                    "A hidden achievement is skipped by the reload that re-derives the others, so " +
                            "deleting the source's row is the only copy of it gone."
                )
                assertTrue(carried.containsKey("apm100"), "The target keeps what it already held.")
                assertEquals(
                    earlier,
                    carried["apm50"],
                    "Earliest wins on an achievement both accounts hold, so merging three accounts " +
                            "settles on the same date whatever order they are merged in."
                )
            }
        } finally {
            leavePlayer(source)
            leavePlayer(target)
        }
    }
}
