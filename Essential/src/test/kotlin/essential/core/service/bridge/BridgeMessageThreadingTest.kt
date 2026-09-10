package essential.core.service.bridge

import PluginTest.Companion.loadGame
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * task-104 / task-174: the bridge client used to call Call.sendMessage straight from its reader
 * coroutine - Dispatchers.IO, a real thread distinct from the main loop, unlike an arc Timer.Task -
 * racing the main thread's own tick over the same connection list. Now wrapped in Core.app.post,
 * the shape TempBan.kt already uses for the same situation, with its own try/catch since arc's
 * TaskQueue calls a posted runnable bare.
 *
 * A genuine cross-thread race isn't something a fast deterministic unit test can force, and this is
 * measured: reverting the Core.app.post wrap does not fail this test (or any other in the scoped
 * run). What it covers is the functional regression - that wrapping the call did not break the
 * relay itself - not the thread-placement claim, which is established from arc's own source (the
 * task files from the original audit, and NetClient.sendMessage's body,
 * which no-ops when Vars.ui is null - true in this headless harness, which is also why nothing here
 * asserts a message was actually displayed) and from an opus review of the diff.
 */
class BridgeMessageThreadingTest {
    private lateinit var originalConf: BridgeConfig

    @BeforeTest
    fun setup() {
        loadGame(true)
        originalConf = BridgeService.conf
    }

    @AfterTest
    fun tearDown() {
        BridgeService.conf = originalConf
    }

    @Test
    fun aClientsBroadcastRoundTripsBackThroughTheServersEcho() {
        val freePort = ServerSocket(0).use { it.localPort }
        BridgeService.conf = BridgeConfig(address = "127.0.0.1", port = freePort, sharedSecret = "t".repeat(32))

        val server = Server.bind(freePort) ?: fail("could not bind the test bridge server on 127.0.0.1:$freePort")
        val serverThread = Thread(server, "bridge-relay-test-server").apply { isDaemon = true; start() }
        val client = Client()

        try {
            client.run()
            assertTrue(waitFor(5000) { isConnected(client) }, "test client never completed the bridge handshake")

            client.message("bridge-relay-probe")
            assertTrue(
                waitFor(5000) { client.lastReceivedMessage == "bridge-relay-probe" },
                "the probe message never came back through the server's echo"
            )
        } finally {
            client.cancel()
            server.shutdown()
            serverThread.interrupt()
        }
    }

    private fun isConnected(client: Client): Boolean {
        val field = Client::class.java.getDeclaredField("isConnected").apply { isAccessible = true }
        return (field.get(client) as AtomicBoolean).get()
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(16)
        }
        return condition()
    }
}
