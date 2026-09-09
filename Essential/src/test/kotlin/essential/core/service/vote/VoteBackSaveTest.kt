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
 * A passed `vote back` must restore the newest of the plugin's own rollback backups, whatever the
 * engine's `autosave` setting says.
 *
 * Two defects lived in the picker, and the tests below cover one each. It ordered the autosave family
 * with arc's `Seq.min`, which keeps the smallest value, so it restored the first file still on disk
 * rather than the last one - [takesTheNewestRollbackSave] guards the ordering. And with `autosave` on it
 * read the autosave family at all, which the plugin neither writes nor clears on a map change, while
 * `/vote back` had already been allowed on the strength of a rollback file existing -
 * [ignoresEngineAutosavesEvenWhenAutosaveIsOn] and [noRollbackSaveIsNoFile] guard that.
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
    fun takesTheNewestRollbackSave() {
        save("rollback_vbt_old.msav", 600_000)
        val newest = save("rollback_vbt_new.msav", 1_000)
        save("rollback_vbt_middle.msav", 300_000)

        assertEquals(newest.name(), findVoteBackSave()?.name(), "vote back restored an older rollback save")
    }

    @Test
    fun ignoresEngineAutosavesEvenWhenAutosaveIsOn() {
        Core.settings.put("autosave", true)
        val rollback = save("rollback_vbt_only.msav", 600_000)
        save("auto_vbt_newer.msav", 1_000)

        assertEquals(rollback.name(), findVoteBackSave()?.name(), "vote back reached for an engine autosave")
    }

    @Test
    fun noRollbackSaveIsNoFile() {
        Core.settings.put("autosave", true)
        save("auto_vbt_only.msav", 1_000)

        assertEquals(null, findVoteBackSave(), "vote back found a save with no rollback backup on disk")
    }
}
