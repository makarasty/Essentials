package essential.common.config

import PluginTest.Companion.loadGame
import arc.util.Log
import essential.common.bundle
import essential.common.rootPath
import kotlinx.serialization.Serializable
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
}
