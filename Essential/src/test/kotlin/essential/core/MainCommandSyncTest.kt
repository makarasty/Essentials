package essential.core

import PluginTest.Companion.loadGame
import arc.util.CommandHandler
import essential.common.rootPath
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Main.syncClientCommands(handler) is the fix for task-171 (banned commands) and task-159/172
 * (the vote/votekick toggle): both used to be applied once, at boot, with no way to re-apply them
 * without a restart. It is a plain function on a fresh CommandHandler, so these tests do not need
 * a live Main instance - only loadGame(true), for rootPath and for the achievement module's
 * generated command registration to exist.
 */
class MainCommandSyncTest {
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
     * Module client commands register through ModuleRuntime.registerClientCommands, which runs
     * after registerGeneratedClientCommands - the exact ordering task-171 is about. Before the
     * fix, banning "ach" (achievements' own command) did nothing: the ban ran first, against a
     * handler that did not have "ach" in it yet, and the module's later registration put it right
     * back with no conflict reported.
     */
    @Test
    fun banningAModuleClientCommandRemovesItEvenThoughTheModuleRegistersAfterTheCoreCommands() {
        val file = rootPath.child("bannedCommands.txt")
        val previous = if (file.exists()) file.readString() else null
        try {
            file.writeString("""["ach"]""", false)

            val handler = CommandHandler("/")
            Main.syncClientCommands(handler)

            assertNull(
                handler.getCommandList().find { it.text == "ach" },
                "banning a module's own client command must remove it"
            )
        } finally {
            if (previous != null) file.writeString(previous, false) else file.delete()
        }
    }

    /**
     * task-172/task-159: disabling feature.vote.enabled removed only the plugin's own renamed
     * command ("evote", since CommandRegistry prefixes it once vanilla's "vote" is already taken)
     * and left vanilla's own /vote and /votekick fully functional. Simulated here by pre-registering
     * "vote"/"votekick" on a fresh handler, standing in for NetServer's vanilla registration.
     */
    @Test
    fun disablingVoteRemovesBothThePluginsRenamedCommandAndVanillasOwnName() {
        val handler = CommandHandler("/")
        handler.register<Any?>("vote", "", "") { _, _ -> }
        handler.register<Any?>("votekick", "", "") { _, _ -> }

        val previousConf = Main.conf
        try {
            Main.conf = Main.conf.copy(
                feature = Main.conf.feature.copy(
                    vote = Main.conf.feature.vote.copy(enabled = false, enableVotekick = false)
                )
            )

            Main.syncClientCommands(handler)

            val names = handler.getCommandList().map { it.text }
            assertNull(names.find { it == "vote" }, "vanilla's own /vote must be removed too, not just the plugin's renamed copy")
            assertNull(names.find { it == "evote" }, "the plugin's own renamed vote command must still be removed")
            assertNull(names.find { it == "votekick" }, "vanilla's own /votekick must be removed too")
            assertNull(names.find { it == "evotekick" }, "the plugin's own renamed votekick command must still be removed")
        } finally {
            Main.conf = previousConf
        }
    }

    /**
     * Control for the test above: re-enabling and syncing again must leave a working /vote
     * command, not a handler a restart is the only way out of.
     *
     * It comes back as "vote" rather than "evote": disabling removed vanilla's own registration
     * (the literal "vote" this test pre-registered, standing in for NetServer's), and nothing in
     * this plugin can put vanilla's command back - CommandHandler.Command's runner is
     * package-private, so there is no reference to re-register it with. With that name free,
     * CommandRegistry's taken check no longer has a reason to prefix the plugin's own command, so
     * it claims "vote" directly instead of "evote". The command still works and every permission
     * node still resolves through the canonical, unprefixed name regardless of which literal text
     * it is registered under - only the exact string a player types changes.
     */
    @Test
    fun enablingVoteAgainRestoresAWorkingCommandWithNoLeftoverState() {
        val handler = CommandHandler("/")
        handler.register<Any?>("vote", "", "") { _, _ -> }
        handler.register<Any?>("votekick", "", "") { _, _ -> }

        val previousConf = Main.conf
        try {
            Main.conf = Main.conf.copy(
                feature = Main.conf.feature.copy(
                    vote = Main.conf.feature.vote.copy(enabled = false, enableVotekick = false)
                )
            )
            Main.syncClientCommands(handler)

            Main.conf = Main.conf.copy(
                feature = Main.conf.feature.copy(
                    vote = Main.conf.feature.vote.copy(enabled = true, enableVotekick = true)
                )
            )
            Main.syncClientCommands(handler)

            val names = handler.getCommandList().map { it.text }
            assertTrue(
                "vote" in names || "evote" in names,
                "re-enabling the feature and syncing again should put a working vote command back, under either name"
            )
        } finally {
            Main.conf = previousConf
        }
    }
}
