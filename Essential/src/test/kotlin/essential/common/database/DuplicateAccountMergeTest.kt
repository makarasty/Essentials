package essential.common.database

import essential.common.database.data.getPlayerData
import essential.common.database.table.PlayerTable
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Flyway V8 deletes every duplicate `account_id` row but the newest. The boot folds them into the
 * newest first, so what V8 would have thrown away is summed onto the survivor instead.
 */
class DuplicateAccountMergeTest {
    companion object {
        private var loaded = false
    }

    @BeforeTest
    fun setup() {
        if (!loaded) {
            PluginTest.loadGame(true)
            loaded = true
        }
    }

    @Test
    fun theOlderDuplicateIsMergedIntoTheNewestRow() = mergesIntoTheNewest("dup-${System.nanoTime()}") { it }

    /** Account lookups ignore case, and so does PostgreSQL's V10 index, so "Bob" and "bob" are one account. */
    @Test
    fun accountsDifferingOnlyInCaseAreMergedToo() = mergesIntoTheNewest("case-${System.nanoTime()}") { it.uppercase() }

    private fun mergesIntoTheNewest(account: String, olderSpelling: (String) -> String) {
        val (older, _) = PluginTest.newPlayer()
        val (newer, _) = PluginTest.newPlayer()

        runBlocking {
            // The boot already ran V8, and its unique index is what normally makes this state impossible.
            suspendTransaction { exec("DROP INDEX IF EXISTS players_account_id_nonempty_unique") }
            try {
                suspendTransaction {
                    PlayerTable.update({ PlayerTable.uuid eq older.uuid() }) {
                        it[accountID] = olderSpelling(account)
                        it[exp] = 10
                        it[lastLoginDate] = LocalDateTime(2020, 1, 1, 0, 0)
                    }
                    PlayerTable.update({ PlayerTable.uuid eq newer.uuid() }) {
                        it[accountID] = account
                        it[exp] = 5
                        it[lastLoginDate] = LocalDateTime(2025, 1, 1, 0, 0)
                    }
                }

                mergeDuplicateAccounts()

                assertNull(getPlayerData(older.uuid()), "the older duplicate is still there")
                val survivor = assertNotNull(getPlayerData(newer.uuid()), "the newest row was the one removed")
                assertEquals(15, survivor.exp, "the older row's exp was dropped instead of merged")
            } finally {
                suspendTransaction {
                    exec("CREATE UNIQUE INDEX IF NOT EXISTS players_account_id_nonempty_unique ON players (account_id)")
                }
            }
        }
    }
}
