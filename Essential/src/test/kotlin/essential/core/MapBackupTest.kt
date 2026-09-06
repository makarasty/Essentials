package essential.core

import PluginTest.Companion.loadGame
import mindustry.Vars
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
        withRollback(enabled = true, mapBackup = true) { Trigger.saveMapBackup() }
        assertTrue(backups().size > 0, "map backup was not written while enabled")
        backups().forEach { it.delete() }
    }
}
