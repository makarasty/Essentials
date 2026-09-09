package essential.common.config

import PluginTest.Companion.loadGame
import arc.util.Log
import essential.common.bundle
import essential.common.rootPath
import kotlinx.serialization.Serializable
import org.junit.Assume.assumeTrue
import java.io.FileOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every failure path in [Config] used to be silent: an unparseable file returned null with nothing logged,
 * a failed directory rename was invisible because [java.io.File.renameTo] reports failure by returning
 * false rather than by throwing, and the migration re-save dropped the operator's unknown keys and their
 * comments without saying so.
 *
 * The expected text is read back out of the bundle rather than written in English here, because the top
 * level [bundle] follows the JVM default locale and these strings are translated.
 */
class ConfigDiagnosticsTest {
    @Serializable
    data class SampleConfig(val alpha: String = "a", val beta: Int = 1)

    companion object {
        private var done = false
        private const val NAME = "fleet_chip3_sample"
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
        previousLogger = Log.logger
        Log.logger = Log.LogHandler { _, text -> lines += text }
    }

    @AfterTest
    fun cleanup() {
        previousLogger?.let { Log.logger = it }
        lines.clear()
        rootPath.child("config/$FILE").delete()
    }

    private fun logged(expected: String) = lines.any { it.contains(expected) }

    private fun write(content: String) {
        rootPath.child("config").mkdirs()
        rootPath.child("config/$FILE").writeString(content, false)
    }

    @Test
    fun unparseableFileIsLoggedRatherThanDegradingSilently() {
        write("alpha: \"x\"\nbeta: not-a-number\n")

        assertNull(Config.load(NAME, SampleConfig.serializer(), null))
        assertTrue(
            logged(bundle["config.parse.failed", FILE]),
            "a malformed config must leave something to search the log for, but the log held: $lines"
        )
    }

    @Test
    fun failedDirectoryRenameIsReported() {
        val old = rootPath.child("configs")
        val current = rootPath.child("config")
        val parked = rootPath.child("config_chip3_parked")

        // A real legacy directory belongs to whoever put it there, so leave rather than delete it.
        assumeTrue("a configs/ directory is already present", !old.exists())
        parked.deleteDirectory()

        val hadConfig = current.exists()
        if (hadConfig) assumeTrue("could not park config/", current.file().renameTo(parked.file()))

        try {
            old.mkdirs()
            old.child("held.yaml").writeString("alpha: \"x\"\n", false)
            // An open handle is what makes renameTo return false on Windows. Elsewhere the rename
            // succeeds and there is nothing to assert, so the test skips rather than passing hollow.
            FileOutputStream(old.child("held.yaml").file()).use {
                Config.renameConfigsDirectory()
                assumeTrue("rename succeeded on this platform", old.exists() && !current.exists())
                assertTrue(
                    logged(bundle["config.migrate.failed", old.absolutePath(), current.absolutePath()]),
                    "a failed rename must be reported, or fresh defaults are written silently: $lines"
                )
            }
        } finally {
            old.deleteDirectory()
            if (hadConfig) {
                current.deleteDirectory()
                // Unchecked here would be the very defect this test is about, and the next line
                // would then delete the only copy.
                check(parked.file().renameTo(current.file())) { "could not restore config/ from $parked" }
            }
            parked.deleteDirectory()
        }
    }
}
