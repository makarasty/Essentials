package essential.core

import PluginTest.Companion.loadGame
import kotlinx.coroutines.runBlocking
import mindustry.Vars
import mindustry.io.SaveIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class MapBackupTest {
    @BeforeTest
    fun setup() {
        loadGame()
        backups().forEach { it.delete() }
    }

    private fun backups() =
        Vars.saveDirectory.findAll { it.name().startsWith("rollback_") && it.name().endsWith(".msav") }

    private fun withRollback(enabled: Boolean, mapBackup: Boolean, action: () -> Unit) {
        val previous = Main.conf
        Main.conf = Main.conf.copy(
            command = Main.conf.command.copy(
                rollback = Main.conf.command.rollback.copy(enabled = enabled, mapBackup = mapBackup)
            )
        )
        try {
            action()
        } finally {
            Main.conf = previous
        }
    }

    @Test
    fun mapBackupDisabledWritesNothing() {
        withRollback(enabled = true, mapBackup = false) { Trigger.saveMapBackup() }
        assertTrue(backups().size == 0, "map backup was written while command.rollback.mapBackup is false")
    }

    @Test
    fun rollbackDisabledWritesNothing() {
        withRollback(enabled = false, mapBackup = true) { Trigger.saveMapBackup() }
        assertTrue(backups().size == 0, "map backup was written while command.rollback.enabled is false")
    }

    @Test
    fun enabledWritesBackup() {
        withRollback(enabled = true, mapBackup = true) { runBlocking { Trigger.saveMapBackup()?.join() } }
        assertTrue(backups().size > 0, "map backup was not written while enabled")
        // Compressed off the game thread by hand rather than by SaveIO.save, so check it still loads.
        assertTrue(backups().all { SaveIO.isSaveValid(it) }, "map backup is not a loadable save")
        backups().forEach { it.delete() }
    }
}
