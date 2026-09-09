package essential.core.service.protect

import essential.common.database.data.getPlayerData
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertNotNull

class AccountRebindTest {
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
    fun a_rebind_that_matches_nothing_keeps_the_data_this_device_already_had() {
        val (player, _) = PluginTest.newPlayer()
        val uuid = player.uuid()

        runBlocking {
            assertNotNull(getPlayerData(uuid), "the test player has no row to begin with")

            assertFails("binding to an account that does not exist should fail") {
                moveAccountToDevice(UInt.MAX_VALUE, uuid, deleteExisting = true)
            }

            assertNotNull(
                getPlayerData(uuid),
                "the device's own data was deleted and the account was never bound in its place"
            )
        }
    }
}
