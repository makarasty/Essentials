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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every failure path in [Config] used to be silent: an unparseable file returned null with nothing logged,
 * a failed directory rename was invisible because [java.io.File.renameTo] reports failure by returning
 * false rather than by throwing, and the migration re-save dropped the operator's unknown keys and their
 * comments without saying so or leaving a copy behind.
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
        rootPath.child("config/$FILE.bak").delete()
        previousLogger = Log.logger
        Log.logger = Log.LogHandler { _, text -> lines += text }
    }

    @AfterTest
    fun cleanup() {
        previousLogger?.let { Log.logger = it }
        lines.clear()
        rootPath.child("config/$FILE").delete()
        rootPath.child("config/$FILE.bak").delete()
    }

    private fun logged(expected: String) = lines.any { it.contains(expected) }

    private fun write(content: String) {
        rootPath.child("config").mkdirs()
        rootPath.child("config/$FILE").writeString(content, false)
    }

    private fun backup() = rootPath.child("config/$FILE.bak")

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
    fun rewriteReportsTheCommentsItIsAboutToDrop() {
        // beta is absent, so the migration re-save runs and writes the whole file from the parsed
        // object. The comment line is not in the canonical output and does not survive that.
        write("# operator note: alpha is deliberate\nalpha: \"x\"\n")

        Config.load(NAME, SampleConfig.serializer(), null)

        assertTrue(
            logged(bundle["config.rewrite.comments", FILE, "1"]),
            "the comment lost to the rewrite must be reported, but the log held: $lines"
        )
    }

    @Test
    fun aMistypedKeyIsReportedEvenWhenNothingIsRewritten() {
        // Every canonical key is present, so no migration re-save is due. Without a warning here the
        // operator never learns that betaa does nothing - the parser is not in strict mode, so it was
        // dropped in silence and the file keeps it forever.
        write("alpha: \"x\"\nbeta: 2\nbetaa: 7\n")

        Config.load(NAME, SampleConfig.serializer(), null)

        assertTrue(
            logged(bundle["config.unknown.keys", FILE, "betaa"]),
            "the unrecognised key must be named, but the log held: $lines"
        )
        assertFalse(
            logged(bundle["config.saved", FILE]),
            "a file with no missing keys must not be rewritten, but the log held: $lines"
        )
    }

    @Test
    fun theFileIsCopiedAsideWhenTheRewriteWouldDiscardSomething() {
        val original = "# operator note: alpha is deliberate\nalpha: \"x\"\nbetaa: 7\n"
        write(original)

        Config.load(NAME, SampleConfig.serializer(), null)

        assertTrue(backup().exists(), "the rewrite discarded a key and a comment, so the file must be recoverable")
        assertEquals(original, backup().readString(), "the backup must hold what the file said before the rewrite")
        assertTrue(
            logged(bundle["config.rewrite.backup", FILE, backup().absolutePath()]),
            "an operator told their comments will not survive must be told where the old file went: $lines"
        )
    }

    @Test
    fun aTrailingCommentAlsoCountsAsSomethingToLose() {
        // The operator's only annotation is on the value line. Matching whole-line comments alone misses
        // it, and the rewrite then deletes it with no warning and, once the backup is gated, no copy.
        val original = "alpha: \"x\"  # do not raise this\n"
        write(original)

        Config.load(NAME, SampleConfig.serializer(), null)

        assertTrue(backup().exists(), "a trailing comment is the operator's too, so the file must be recoverable")
        assertEquals(original, backup().readString())
    }

    @Test
    fun anEarlierBackupIsNotReplacedByALaterRewrite() {
        // A build that retires a key makes an already-canonical file look like it carries an unknown one,
        // so a second rewrite runs at a point where nothing of the operator's is left in the file.
        // Overwriting the backup then would destroy their only copy.
        val original = "# operator note: alpha is deliberate\nalpha: \"x\"\nbetaa: 7\n"
        write(original)
        Config.load(NAME, SampleConfig.serializer(), null)

        write("alpha: \"y\"\nretired: 1\n")
        Config.load(NAME, SampleConfig.serializer(), null)

        assertEquals(
            original,
            backup().readString(),
            "the first backup is closest to what the operator wrote and must survive later rewrites"
        )
        assertTrue(
            logged(bundle["config.backup.kept", FILE, backup().absolutePath()]),
            "keeping the older backup must be said out loud, but the log held: $lines"
        )
    }

    @Test
    fun aRewriteThatDiscardsNothingWritesNoBackup() {
        // beta is missing so the re-save still runs, but there is no unknown key and no comment of the
        // operator's to lose. Backing up unconditionally would mean the boot after a real rewrite
        // overwrote the good backup with the already-canonical file, leaving the operator holding a
        // copy of exactly what they lost.
        write("alpha: \"x\"\n")

        Config.load(NAME, SampleConfig.serializer(), null)

        assertTrue(logged(bundle["config.saved", FILE]), "the migration re-save should still have run: $lines")
        assertFalse(
            backup().exists(),
            "nothing was discarded, so a backup would only overwrite a good one on a later boot"
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
