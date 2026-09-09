package essential.core.service.web

import essential.common.database.data.PlayerData
import essential.common.rootPath
import essential.core.service.chat.ChatService
import essential.core.service.web.auth.LoginRequest
import essential.core.service.web.auth.SessionRevocations
import essential.core.service.web.auth.UserSession
import essential.core.service.web.auth.isCurrent
import essential.core.service.web.maps.MapController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The web surface authenticates real accounts, so each of these describes a way in that used to be open:
 * an unauthenticated caller reading account state out of the login responses, a session that outlived
 * both its configured lifetime and its owner's logout, and a message reaching game chat without any of
 * the filters an in-game message goes through.
 */
class WebHardeningTest {
    private lateinit var server: WebServer

    @BeforeTest
    fun setup() {
        PluginTest.loadGame(true)
        server = WebServer()
        server.start()
    }

    @AfterTest
    fun cleanup() {
        server.stop()
    }

    private class Response(val status: Int, val body: String, val setCookie: String?)

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        cookie: String? = null,
        contentType: String = "application/json"
    ): Response {
        val connection =
            URI("http://127.0.0.1:${server.boundPort}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        // Nothing here should take seconds. A handler that reaches onGameThread would otherwise wait
        // forever, because the test harness only drains Core.app when pumpApp is called.
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() } ?: ""
            return Response(status, text, connection.getHeaderField("Set-Cookie"))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A message that gets past the guards is broadcast through onGameThread, and Core.app's queue is only
     * drained when a test pumps it. Every expectation of a 200 from the chat endpoint runs inside this, so
     * that a 403 elsewhere in these tests is known to come from the guard rather than from the endpoint
     * being closed to everyone.
     */
    private fun <T> pumping(block: () -> T): T {
        val stop = AtomicBoolean(false)
        val pump = Thread {
            while (!stop.get()) {
                PluginTest.pumpApp()
                Thread.sleep(16)
            }
        }
        pump.isDaemon = true
        pump.start()
        try {
            return block()
        } finally {
            stop.set(true)
            pump.join(2000)
        }
    }

    private fun loginBody(username: String, password: String) =
        Json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))

    /** A player whose account is set up far enough that a correct password logs in. */
    private fun registeredPlayer(password: String): PlayerData {
        val data = PluginTest.newPlayer().second
        data.accountID = "account-${System.nanoTime()}"
        data.accountPW = BCrypt.hashpw(password, BCrypt.gensalt())
        data.discordID = "discord-${System.nanoTime()}"
        runBlocking { data.update() }
        return data
    }

    private fun sessionCookieFor(data: PlayerData, password: String): String {
        val login = request("POST", "/api/auth/login", loginBody(data.name, password))
        assertEquals(200, login.status, "a correct password no longer logs in: ${login.body}")
        return login.setCookie?.substringBefore(';') ?: fail("login returned no session cookie")
    }

    @Test
    fun every_failed_login_answers_the_same_thing() {
        val existing = PluginTest.newPlayer().second

        val unknown = request(
            "POST",
            "/api/auth/login",
            loginBody("no-such-player-${System.nanoTime()}", "wrong")
        )
        val notSetUp = request("POST", "/api/auth/login", loginBody(existing.name, "wrong"))

        assertEquals(401, unknown.status)
        assertEquals(401, notSetUp.status, "an existing player is answered differently from an unknown one")
        assertEquals(
            unknown.body,
            notSetUp.body,
            "the login response tells an unauthenticated caller whether the name exists"
        )
    }

    @Test
    fun the_stored_account_id_is_not_an_oracle() {
        val data = registeredPlayer("correct horse battery staple")
        val accountID = data.accountID ?: fail("account ID was not stored")

        val guess = request("POST", "/api/auth/login", loginBody(data.name, accountID))
        val otherGuess = request("POST", "/api/auth/login", loginBody(data.name, "some other guess"))

        assertEquals(401, guess.status, "the stored account ID submitted as a password is answered as its own case")
        assertEquals(guess.body, otherGuess.body, "a guess equal to the account ID is distinguishable from any other")
    }

    @Test
    fun a_muted_player_cannot_talk_through_the_web_panel() {
        val password = "web-panel-password"
        val data = registeredPlayer(password)
        val cookie = sessionCookieFor(data, password)

        val allowed = pumping { request("POST", "/api/server/chat", "hello everyone", cookie, "text/plain") }
        assertEquals(200, allowed.status, "an ordinary web message was refused: ${allowed.body}")

        data.chatMuted = true
        runBlocking { data.update() }

        val posted = request("POST", "/api/server/chat", "hello everyone", cookie, "text/plain")
        assertEquals(403, posted.status, "a muted player's message was broadcast from the web panel")
    }

    @Test
    fun a_blacklisted_message_is_refused_from_the_web_panel() {
        val password = "web-panel-password"
        val data = registeredPlayer(password)
        val cookie = sessionCookieFor(data, password)

        val blacklist = rootPath.child("chat_blacklist.txt")
        val savedList = if (blacklist.exists()) blacklist.readString("UTF-8") else null
        val savedEnabled = ChatService.conf.blacklist.enabled
        try {
            blacklist.writeString("forbiddenword")
            ChatService.conf.blacklist.enabled = true

            val clean = pumping { request("POST", "/api/server/chat", "a clean message", cookie, "text/plain") }
            assertEquals(200, clean.status, "an unlisted message was refused: ${clean.body}")

            val posted = request("POST", "/api/server/chat", "a forbiddenword here", cookie, "text/plain")
            assertEquals(403, posted.status, "the web chat endpoint broadcast a blacklisted message")
        } finally {
            ChatService.conf.blacklist.enabled = savedEnabled
            savedList?.let { blacklist.writeString(it) }
        }
    }

    @Test
    fun a_session_expires_and_a_logout_revokes_the_copies_of_it() {
        val saved = WebService.conf
        try {
            WebService.conf = saved.copy(sessionDuration = 60)
            val now = System.currentTimeMillis()

            assertTrue(UserSession("1", "web-session-user", now).isCurrent())
            assertFalse(
                UserSession("1", "web-session-user", now - 61_000).isCurrent(),
                "a session older than sessionDuration still authenticated"
            )
            assertFalse(
                UserSession("1", "web-session-user").isCurrent(),
                "a cookie carrying no issue time still authenticated"
            )

            val copied = UserSession("1", "web-session-user", System.currentTimeMillis())
            assertTrue(copied.isCurrent())
            SessionRevocations.revoke(copied)
            assertFalse(copied.isCurrent(), "a copied cookie survived the logout of its owner")

            // Revocation is per account row, not per name: another row must be untouched by it.
            assertTrue(UserSession("2", "web-session-user", System.currentTimeMillis()).isCurrent())
        } finally {
            WebService.conf = saved
        }
    }

    @Test
    fun a_web_message_cannot_paint_itself_in_mindustry_markup() {
        val password = "web-panel-password"
        val data = registeredPlayer(password)
        val cookie = sessionCookieFor(data, password)

        val marker = "marker${System.nanoTime()}"
        val posted = pumping { request("POST", "/api/server/chat", "[coral]$marker", cookie, "text/plain") }
        assertEquals(200, posted.status, "an ordinary web message was refused: ${posted.body}")

        val history = request("GET", "/api/server/chat", cookie = cookie).body
        assertTrue(
            history.contains("[[coral]$marker"),
            "a colour tag reached game chat unescaped, so a web message can forge a server line: $history"
        )
    }

    @Test
    fun logout_stops_every_copy_of_the_cookie_not_just_the_caller_s() {
        val password = "web-panel-password"
        val data = registeredPlayer(password)
        val cookie = sessionCookieFor(data, password)

        assertEquals(200, request("GET", "/api/auth/status", cookie = cookie).status)
        assertEquals(200, request("GET", "/api/auth/logout", cookie = cookie).status)

        val replayed = request("GET", "/api/auth/status", cookie = cookie)
        assertEquals(401, replayed.status, "a copy of the cookie still authenticated after its owner logged out")
    }

    @Test
    fun re_uploading_a_map_name_you_do_not_own_is_refused() {
        val controller = MapController()

        assertTrue(controller.mayReplaceExisting("alice", fileExists = false, fileOwner = null, nameOwner = null))
        assertTrue(controller.mayReplaceExisting("alice", fileExists = true, fileOwner = "Alice", nameOwner = "alice"))
        assertFalse(
            controller.mayReplaceExisting("bob", fileExists = true, fileOwner = "alice", nameOwner = "alice"),
            "another account could overwrite a map file it does not own"
        )
        assertFalse(
            controller.mayReplaceExisting("bob", fileExists = false, fileOwner = null, nameOwner = "alice"),
            "another account could take over the uploader record, and with it the delete guard"
        )
        assertFalse(
            controller.mayReplaceExisting("bob", fileExists = true, fileOwner = null, nameOwner = null),
            "a map with no uploader record could be claimed by re-uploading over it"
        )
    }
}
