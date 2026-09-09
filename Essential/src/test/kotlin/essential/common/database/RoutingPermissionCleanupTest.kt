package essential.common.database

import PluginTest.Companion.loadGame
import essential.common.database.data.grantRoutingPermission
import essential.common.database.table.ServerRoutingTable
import essential.common.systemTimezone
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.toLocalDateTime

/**
 * Expired routing permissions were never deleted on a running server.
 *
 * `cleanupExpiredRoutingPermissions` had one caller, sitting after the ping thread's `while` loop -
 * and `shutdownNow` interrupts that thread inside its `sleep(3000)`, so control jumps from the sleep
 * to the `InterruptedException` handler and past the call. Rows were only ever inserted. Granting is
 * what every warp move and AFK transfer goes through, so the delete now happens there.
 */
@OptIn(ExperimentalTime::class)
class RoutingPermissionCleanupTest {
    private val uuid = "routing-cleanup-test"

    @BeforeTest
    fun setup() {
        loadGame(true)
        clearRows()
    }

    @AfterTest
    fun teardown() = clearRows()

    private fun clearRows() = runBlocking {
        suspendTransaction { ServerRoutingTable.deleteWhere { playerUuid eq uuid } }
        Unit
    }

    private fun rowCount(): Int = runBlocking {
        suspendTransaction {
            ServerRoutingTable.selectAll().where { ServerRoutingTable.playerUuid eq uuid }.count().toInt()
        }
    }

    private fun grant(validSeconds: Int) = runBlocking {
        grantRoutingPermission(
            playerUuid = uuid,
            hubServerName = "hub",
            targetServerName = "target",
            targetPort = 6567,
            hubConnectionTime = Clock.System.now().toLocalDateTime(systemTimezone),
            validSeconds = validSeconds
        )
    }

    @Test
    fun grantingDeletesThePermissionsThatHaveAlreadyExpired() {
        // Already expired the moment it is written, which is what every permission looks like a minute
        // after it was granted.
        grant(-30)
        assertEquals(1, rowCount(), "the expired fixture row was not written, so this proves nothing")

        grant(60)

        assertEquals(
            1, rowCount(),
            "the expired row survived a later grant, so nothing ever deletes one and the table only grows"
        )
    }
}
