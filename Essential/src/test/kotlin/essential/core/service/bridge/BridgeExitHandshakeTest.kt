package essential.core.service.bridge

import PluginTest.Companion.loadGame
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * task-105, the "exit" third of it: `Client.send("exit")` used to map straight to
 * `closeConnection()`, which closes the local socket and never writes the word "exit" to the wire -
 * so the peer's own `"exit" -> break` handler in [Server] could never fire from a clean client
 * shutdown, only ever learn of it from the ensuing socket close. `BridgeService.dispose()` calls
 * `send("exit")` believing it tells the peer; it did not.
 *
 * This drives a raw socket as the peer, rather than a real [Server], so what is asserted is
 * specifically the literal bytes [Client] puts on the wire - not the round trip through a real
 * server's own handling of "exit" (which was already reachable, just never reached by this path).
 */
class BridgeExitHandshakeTest {
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
    fun theClientWritesTheLiteralExitCommandToTheWireBeforeClosing() {
        val rawServer = ServerSocket(0)
        val port = rawServer.localPort
        BridgeService.conf = BridgeConfig(address = "127.0.0.1", port = port, sharedSecret = "x".repeat(32))

        val client = Client()
        val receivedLines = mutableListOf<String>()
        val peerThread = Thread {
            try {
                rawServer.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

                    val challenge = createBridgeChallenge()
                    writer.write("auth-challenge")
                    writer.newLine()
                    writer.write(challenge)
                    writer.newLine()
                    writer.flush()

                    readBridgeLine(reader) // "auth-response"
                    readBridgeLine(reader) // the HMAC response, unchecked - this peer is not Server

                    readBridgeLine(reader)?.let { receivedLines.add(it) }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        try {
            client.run()
            assertTrue(waitFor(5000) { isConnected(client) }, "test client never completed the bridge handshake")

            client.send("exit")

            assertTrue(
                waitFor(5000) { receivedLines.isNotEmpty() },
                "the peer never received anything after send(\"exit\") - the command never reached the wire"
            )
            assertEquals(
                "exit",
                receivedLines.first(),
                "send(\"exit\") must write the literal command word, not just close the local socket"
            )
        } finally {
            client.cancel()
            rawServer.close()
            peerThread.interrupt()
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
