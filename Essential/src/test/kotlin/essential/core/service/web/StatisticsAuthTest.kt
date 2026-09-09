package essential.core.service.web

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatisticsAuthTest {
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

    private fun statusOf(path: String): Int {
        val connection = URI("http://127.0.0.1:${server.boundPort}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        try {
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun no_statistics_endpoint_answers_without_a_session() {
        for (path in listOf("/api/server/status", "/api/server/contribution", "/api/server/chat", "/api/server/history")) {
            assertEquals(401, statusOf(path), "$path answered a caller with no session")
        }
    }
}
