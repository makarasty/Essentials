package essential.common.config

import PluginTest.Companion.loadGame
import arc.util.Log
import essential.common.bundle
import essential.common.rootPath
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Fi.writeString][arc.files.Fi.writeString] throws arc's `ArcRuntimeException`, not [java.io.IOException]
 * or [kotlinx.serialization.SerializationException] - the two types `Config.save` used to catch. Uncaught,
 * that escaped `save` and, depending on the caller, either surfaced as a mislabelled "Error migrating
 * config" (the re-save path in `load`) or escaped `load` entirely and took the boot down with it (the
 * first-run default-config path). A read-only directory or a full disk is what triggers it for real; a
 * directory sitting where the config file should be reproduces the same `ArcRuntimeException` from
 * [arc.files.Fi.writer] without needing either.
 */
class ConfigSaveArcExceptionTest {
    @Serializable
    data class SampleConfig(val alpha: String = "a")

    companion object {
        private var done = false
        private const val NAME = "fleet_chip15_arc_sample"
        private const val FILE = "$NAME.yaml"
    }

    private val lines = mutableListOf<String>()
    private var previousLogger: Log.LogHandler? = null

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
        rootPath.child("config").mkdirs()
        rootPath.child("config/$FILE").deleteDirectory()
        rootPath.child("config/$FILE").delete()
        previousLogger = Log.logger
        Log.logger = Log.LogHandler { _, text -> lines += text }
    }

    @AfterTest
    fun cleanup() {
        previousLogger?.let { Log.logger = it }
        lines.clear()
        rootPath.child("config/$FILE").deleteDirectory()
        rootPath.child("config/$FILE").delete()
    }

    @Test
    fun aWriteFailureArcThrowsIsReportedRatherThanEscaping() {
        // A directory where the file should be makes Fi.writer()'s FileWriter open fail with an
        // IOException, which Fi wraps and rethrows as ArcRuntimeException - the same exception class
        // Fi.writeString throws for a read-only directory or a full disk.
        rootPath.child("config/$FILE").mkdirs()

        val saved = Config.save(FILE, SampleConfig.serializer(), SampleConfig())

        assertFalse(saved, "a directory in the file's place cannot be written to")
        assertTrue(
            lines.any { it.contains(bundle["config.save.failed", FILE]) },
            "the operator must be told which file could not be written and why, but the log held: $lines"
        )
    }
}
