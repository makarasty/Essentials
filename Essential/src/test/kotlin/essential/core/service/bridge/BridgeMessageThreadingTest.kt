package essential.core.service.bridge

import PluginTest.Companion.loadGame
import PluginTest.Companion.pumpApp
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * task-104 / task-174: the bridge client used to call Call.sendMessage straight from its reader
 * coroutine - Dispatchers.IO, a real thread distinct from the main loop, unlike an arc Timer.Task -
 * racing the main thread's own tick over the same connection list. Now wrapped in Core.app.post,
 * the shape TempBan.kt already uses for the same situation, with its own try/catch since arc's
 * TaskQueue calls a posted runnable bare.
 *
 * task-103: the server's relay wrote to sockets only, so a broadcast from a client server never
 * displayed on the bridge host itself - Commands.kt's own /broadcast server branch does both a
 * sendAll and a local Call.sendMessage, and this path only did the first. Now posted the same way.
 *
 * A genuine cross-thread race isn't something a fast deterministic unit test can force, and this is
 * measured: reverting either Core.app.post wrap does not fail either test here (or any other in the
 * scoped run). What they cover is the functional regression - that wrapping the call, and adding
 * the host's own local display, did not break the relay itself - not the thread-placement or
 * display claims, which are established from arc's own source (the task files under
 * the original audit, and NetClient.sendMessage's body, which no-ops
 * when Vars.ui is null - true in this headless harness, which is also why nothing here asserts a
 * message was actually displayed) and from an opus review of the diff.
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
            // The received message posts a Call.sendMessage to Core.app's queue, which nothing in
            // this test pumps - drain it here rather than leave it to run against whatever class the
            // suite happens to load next, where a failure would log through that class's guard.
            pumpApp()
        }
    }

    /**
     * task-103 specifically: speaks the wire protocol directly with a raw socket rather than the
     * Client class, so this exercises only the server's relay handler.
     */
    @Test
    fun theServerAcceptsAndRelaysAClientBroadcastWithoutClosingTheConnection() {
        val freePort = ServerSocket(0).use { it.localPort }
        BridgeService.conf = BridgeConfig(address = "127.0.0.1", port = freePort, sharedSecret = "h".repeat(32))

        val server = Server.bind(freePort) ?: fail("could not bind the test bridge server on 127.0.0.1:$freePort")
        val serverThread = Thread(server, "bridge-host-display-test-server").apply { isDaemon = true; start() }
        var socket: Socket? = null

        try {
            socket = Socket("127.0.0.1", freePort)
            // readBridgeLine blocks indefinitely by design - it is also the client/server's normal
            // read loop, which legitimately waits forever for the next message. On this test's own
            // thread, with no server response coming, that turned a broken assertion into a wedged
            // JVM (confirmed: reverting the server's relay to prove the assertion below is real hung
            // this test rather than failing it, and outlived the test process itself). A per-socket
            // read timeout keeps a real defect here a fast, named failure instead.
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            val command = readBridgeLine(reader)
            assertTrue(command == "auth-challenge", "server did not offer a challenge")
            val challenge = requireNotNull(readBridgeLine(reader)) { "server sent no challenge value" }
            writer.write("auth-response")
            writer.newLine()
            writer.write(bridgeAuthenticationResponse(BridgeService.conf.sharedSecret, challenge))
            writer.newLine()
            writer.flush()

            writer.write("message")
            writer.newLine()
            writer.write(encodeBridgePayload("host-display-probe"))
            writer.newLine()
            writer.flush()

            // The relay's local display runs on the game thread via Core.app.post, which nothing
            // pumps in this test - so what is checked here is not that a display actually rendered,
            // but that the handler ran to completion and the relay it always did still works: this
            // raw socket is itself in `server.clients` (added at connect time), so sendAll's echo
            // sends the relayed message straight back to it. Reading that echo back, rather than
            // only checking the connection survived, is load-bearing - a version of this test that
            // asserted `server.clients.isNotEmpty()` alone stayed green with the entire "message"
            // branch deleted, an opus reviewer caught it in the earlier commit that added it.
            val echoedCommand = readBridgeLine(reader)
            assertEquals("message", echoedCommand, "the server must still echo the relayed command back")
            val echoedPayload = readBridgeLine(reader)?.let(::decodeBridgePayload)
            assertEquals(
                "host-display-probe",
                echoedPayload,
                "the server must still relay the broadcast payload unchanged after the local-display fix"
            )
            assertTrue(
                waitFor(2000) { server.clients.isNotEmpty() },
                "the connection must still be tracked as an open client after a relayed broadcast"
            )
        } finally {
            socket?.close()
            server.shutdown()
            serverThread.interrupt()
            // Same reason as the test above: the relay posts its own Call.sendMessage.
            pumpApp()
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
