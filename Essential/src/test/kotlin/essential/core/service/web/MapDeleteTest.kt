package essential.core.service.web

import arc.Core
import arc.Settings
import arc.files.Fi
import essential.core.service.web.maps.MapController
import essential.core.service.web.maps.MapUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class MapDeleteTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("essentials_test").toFile()
        Core.settings = Settings()
        Core.settings.dataDirectory = Fi(tempDir.absolutePath)
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testMapControllerInitCreatesDirectoriesAndLoadsUploaders() {
        val controller = MapController()

        // Create dummy uploaders json
        val uploadersFile = controller.uploadersFile
        uploadersFile.parentFile.mkdirs()
        val dummyData = mapOf("TestMap" to "testerUser", "AnotherMap" to "admin")
        uploadersFile.writeText(Json.encodeToString(dummyData))

        val scope = CoroutineScope(Dispatchers.Default)
        controller.init(scope)

        assertTrue(controller.webCacheDir.exists())
        assertTrue(uploadersFile.exists())
        // The old file format named only the uploader's player name, which no longer identifies an account.
        assertEquals(MapUploader(null, "testerUser"), controller.uploadersMap["TestMap"])
    }

    @Test
    fun uploaderRecordsLoadWithTheirAccount() {
        val controller = MapController()
        controller.uploadersFile.parentFile.mkdirs()
        val record = MapUploader("tester-account", "testerUser")
        controller.uploadersFile.writeText(Json.encodeToString(mapOf("TestMap" to record)))

        controller.init(CoroutineScope(Dispatchers.Default))

        assertEquals(record, controller.uploadersMap["TestMap"])
    }
}
