package essential.common.util

import PluginTest.Companion.loadGame
import essential.common.database.table.PlayerTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The offline lookup's primary query had no row limit while the fallback scan beside it did, so a short
 * query read the whole shared table into memory. Capping it is only half of it: a query whose own result
 * set was capped holds an arbitrary subset, so the single row it happens to carry cannot be handed back
 * as "the" match - callers act on [PlayerLookup.Result.Found] directly and one of them bans it.
 *
 * The fallback scan's cap is a different thing wearing the same word. It is reached on any table larger
 * than [PlayerLookup.SCAN_LIMIT] whatever the query was, and its whole purpose is to recover a player
 * whose colour codes hide the query from a raw LIKE. Refusing those would take the feature away from
 * every large table, which is the second half of this test.
 */
class PlayerLookupTruncationTest {
    companion object {
        private var done = false
        private const val PREFIX = "qlxcap"
        private const val HIDDEN = "qlxhidden"
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        purge()
    }

    @AfterTest
    fun cleanup() = purge()

    private fun purge() = runBlocking {
        suspendTransaction {
            PlayerTable.deleteWhere { uuid like LikePattern("$PREFIX-%") }
        }
        Unit
    }

    @Test
    fun aCappedQueryReportsItWhileTheCappedScanStillResolves() {
        runBlocking {
            suspendTransaction {
                // First, so the uncapped-scan pass below reaches it: the scan has no ORDER BY.
                // The colour codes fall inside the searched span, so a raw LIKE cannot match it and
                // only the stripped-name fallback can.
                PlayerTable.insert {
                    it[name] = "[red]q[white]lxhidden"
                    it[uuid] = "$PREFIX-hidden"
                }
                // Exactly SCAN_LIMIT rows matching PREFIX, one of them an exact name match, so the
                // primary query hits its own cap and pick() still resolves a single row from it.
                PlayerTable.insert {
                    it[name] = PREFIX
                    it[uuid] = "$PREFIX-exact"
                }
                repeat(PlayerLookup.SCAN_LIMIT - 1) { i ->
                    PlayerTable.insert {
                        it[name] = "$PREFIX${i + 1}"
                        it[uuid] = "$PREFIX-${i + 1}"
                    }
                }
            }

            when (val capped = PlayerLookup.findOffline(PREFIX)) {
                is PlayerLookup.Result.Ambiguous -> {
                    assertTrue(capped.truncated, "a capped query must say so rather than look like an ordinary match")
                    assertTrue(
                        capped.candidates.any { it.contains("$PREFIX-exact") },
                        "the answer must carry the full uuid it tells the admin to use, but was ${capped.candidates}"
                    )
                }

                is PlayerLookup.Result.Found ->
                    fail("a capped query named one player as the match: ${capped.value.name} (${capped.value.uuid})")

                is PlayerLookup.Result.NotFound ->
                    fail("expected a truncated ambiguous answer but nothing was found")
            }

            val hidden = PlayerLookup.findOffline(HIDDEN)
            assumeTrue("the capped scan did not sample the colour-coded row", hidden !is PlayerLookup.Result.NotFound)
            assertTrue(
                hidden is PlayerLookup.Result.Found && hidden.value.uuid == "$PREFIX-hidden",
                "a colour-hidden name recovered by the capped scan must still name the player, but was $hidden"
            )
        }
    }
}
