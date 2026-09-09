package essential.core

import PluginTest.Companion.loadGame
import arc.util.CommandHandler
import arc.util.Log
import essential.common.permission.Permission
import essential.common.rootPath
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class BootTimePermissionValidationTest {
    companion object {
        private var done = false
    }

    @BeforeTest
    fun setup() {
        if (!done) {
            loadGame(true)
            done = true
        }
    }

    /**
     * task-154: Permission.validate(known) reports an old-fork-style e-prefixed node such as
     * "evote" only when the un-prefixed name ("vote") is in `known`. CoreEvent.kt's serverLoad
     * built `known` from the client command handler at ServerLoadEvent, which fires before
     * NetServer.init() registers any client command (established while closing this task) - so
     * `known` never held a plugin command name and the warning could never fire, on any server,
     * for any command. registerClientCommands now calls Permission.validate itself, after
     * syncClientCommands has actually populated the handler - this seeds a role holding "evote"
     * and confirms the warning fires this time.
     */
    @Test
    fun anOldForkStyleEPrefixedNodeIsReportedOnceClientCommandsAreRegistered() {
        val mainFile = rootPath.child("permission.yaml")
        val backup = mainFile.readString()
        val previousLogger = Log.logger
        val lines = mutableListOf<String>()
        try {
            mainFile.writeString(
                """
                carried_over:
                    permission:
                        - evote
                """.trimIndent(),
                false
            )
            Permission.load()

            Log.logger = Log.LogHandler { level, text ->
                previousLogger.log(level, text)
                lines.add(text)
            }

            Main().registerClientCommands(CommandHandler("/"))

            assertTrue(
                lines.any { it.contains("evote") && it.contains("vote") },
                "an old-fork 'evote' permission node should be reported once client commands are " +
                    "registered, but the log had: $lines"
            )
        } finally {
            Log.logger = previousLogger
            mainFile.writeString(backup, false)
            Permission.load()
        }
    }
}
