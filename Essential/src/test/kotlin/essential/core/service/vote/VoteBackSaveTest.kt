package essential.core.service.vote

import PluginTest.Companion.loadGame
import arc.Core
import arc.files.Fi
import mindustry.Vars
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A passed `vote back` must restore the most recent save, never the oldest one on disk.
 *
 * Before the fix the autosave branch used arc's `Seq.min`, which keeps the smallest value, so the
 * server was rolled back to the first autosave still on disk - possibly one written under a
 * different map - while the rollback branch of the same expression took the newest file.
 */
class VoteBackSaveTest {
    private val written = mutableListOf<Fi>()

    @BeforeTest
    fun setup() {
        loadGame()
        clean()
    }

    @AfterTest
    fun tearDown() {
        clean()
        Core.settings.put("autosave", false)
    }

    private fun clean() {
        written.forEach { if (it.exists()) it.delete() }
        written.clear()
        // Trigger.saveMapBackup() and the engine's own autosave write into this directory from other
        // tests in the same JVM, and a file written seconds ago would outrank every fixture below.
        Vars.saveDirectory
            .findAll { it.name().startsWith("auto_") || it.name().startsWith("rollback_") }
            .forEach { it.delete() }
    }

    /** Writes a placeholder save whose modification time is [ageMillis] before now. */
    private fun save(name: String, ageMillis: Long): Fi {
        val file = Vars.saveDirectory.child(name)
        file.writeString("save")
        check(file.file().setLastModified(System.currentTimeMillis() - ageMillis)) {
            "could not age ${file.name()}, the fixture would be indistinguishable from the others"
        }
        written.add(file)
        return file
    }

    @Test
    fun autosaveBranchTakesTheNewestFile() {
        Core.settings.put("autosave", true)
        save("auto_vbt_old.msav", 600_000)
        val newest = save("auto_vbt_new.msav", 1_000)
        save("auto_vbt_middle.msav", 300_000)

        assertEquals(newest.name(), findVoteBackSave()?.name(), "vote back restored an older autosave")
    }

    @Test
    fun rollbackBranchTakesTheNewestFile() {
        Core.settings.put("autosave", false)
        save("rollback_vbt_old.msav", 600_000)
        val newest = save("rollback_vbt_new.msav", 1_000)

        assertEquals(newest.name(), findVoteBackSave()?.name(), "vote back restored an older rollback save")
    }
}
