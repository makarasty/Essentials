package essential.core

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import essential.common.permission.Permission
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shipped permission file and the nodes the code asks for, held against each other.
 *
 * Both directions fail closed and are therefore invisible on a running server: a node in the file
 * that nothing asks for grants nothing while reading as if it granted something, and a node the code
 * asks for that no group lists is a command only the owner can run. Five of these were found by an
 * audit rather than by anybody playing, after years - `achievements` for a command named `ach`, a
 * bare `admin`, and `hub`, `hub.build`, `nextmap`, `nextmap.admin` missing outright.
 *
 * `Permission.validate`, the boot-time check that exists, cannot find any of them. It reports a node
 * only when dropping the fork's `e` prefix turns it into a known one, so a node that is simply wrong
 * is skipped; and it only ever walks the file asking what the code does not know, never the code
 * asking what the file does not grant. This runs at build time, which is also the better place: a
 * config that cannot work should stop the jar, not print a line on six live servers.
 */
class PermissionNodeInventoryTest {
    companion object {
        /**
         * Nodes asked for directly in code rather than carried by a command's own name.
         *
         * The other copy of this list is in `essential/core/CoreEvent.kt`, in the `serverLoad`
         * handler, where it seeds the set handed to `Permission.validate`. Add to both.
         */
        private val SUB_NODES = setOf(
            "admin", "afk.admin", "chat.admin", "hub.build", "info.other", "kick.admin",
            "kill.other", "nextmap.admin", "pm.other", "pvp.spector", "team.other",
            "vote.admin", "vote.back", "vote.draw", "vote.gg", "vote.kick", "vote.pass",
            "vote.map", "vote.random", "vote.random.bypass", "vote.reset", "vote.skip",
        )

        /**
         * Mindustry's own client commands, taken from the v159.7 `NetServer` constant pool.
         *
         * `/help` runs every registered command past `check` to decide what to list
         * (`core/Commands.kt:493`, through `CommandRegistry.canonical`), vanilla's included, so
         * granting one of these is a legitimate thing for an operator to do even though no
         * `@ClientCommand` in this tree declares it. They belong on the "something asks for it" side
         * and not on the "we should have granted it" side.
         */
        private val VANILLA_NODES = setOf("help", "t", "a", "votekick", "vote", "sync")

        /**
         * Nodes no group in the shipped file holds, so today only a group granting `all` can use them.
         *
         * A command added without a grant, or a grant added without removing its entry here, fails
         * this test rather than shipping quietly.
         */
        private val ONLY_OWNER = setOf(
            "broadcast", "changename", "exp", "fuck", "hub.build", "js", "kickall", "killall",
            "killunit", "log", "setitem", "setperm", "unban", "vote.admin", "vote.random.bypass",
            "votekick", "ws",
        )

        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    }

    /**
     * Every `@ClientCommand` name in the tree; the generated dispatcher checks a node of that name.
     *
     * Fails rather than skipping an annotation it cannot read. A dropped name is otherwise the one
     * way past both assertions below - it leaves the code side and the file side agreeing about a
     * command neither of them has heard of.
     */
    private fun commandNodes(): Set<String> {
        val sources = File("src/main/kotlin")
        assertTrue(sources.isDirectory, "expected to run from the Essential project directory, saw ${File("").absolutePath}")

        val nodes = mutableSetOf<String>()
        val start = Regex("""@ClientCommand\s*\(""")
        val named = Regex("""name\s*=\s*"([^"]+)"""")
        val positional = Regex("""^\s*"([^"]+)"""")
        for (file in sources.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            for (match in start.findAll(text)) {
                var depth = 1
                var i = match.range.last + 1
                while (depth > 0 && i < text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    i++
                }
                val arguments = text.substring(match.range.last + 1, i - 1)
                val name = named.find(arguments) ?: positional.find(arguments)
                    ?: fail("could not read a command name out of the @ClientCommand in ${file.name}: ${arguments.take(120)}")
                nodes.add(name.groupValues[1])
            }
        }

        assertTrue(nodes.size > 40, "the @ClientCommand scan found only ${nodes.size} commands, so it walked the wrong tree")
        return nodes
    }

    private fun grantedNodes(): Set<String> {
        val text = Permission::class.java.getResourceAsStream("/permission_default.yaml")
            ?.bufferedReader()?.use { it.readText() }
        assertTrue(text != null && text.isNotBlank(), "permission_default.yaml must ship inside the jar")

        val roles = yaml.decodeFromString(
            MapSerializer(String.serializer(), Permission.RoleConfig.serializer()),
            text
        )
        return roles.values.flatMap { it.permission }.toSet() - "all"
    }

    @Test
    fun everyNodeInTheShippedFileIsOneSomethingAsksFor() {
        assertEquals(
            emptySet(),
            grantedNodes() - commandNodes() - SUB_NODES - VANILLA_NODES,
            "these nodes are granted by permission_default.yaml and no command name and no Permission.check call site asks for them, so they grant nothing. Either the node is misspelled - the command name is the node - or the command it was written for is gone and the line should go with it"
        )
    }

    @Test
    fun everyNodeTheCodeAsksForIsGrantedToSomeGroupOrWrittenDownAsOwnerOnly() {
        assertEquals(
            ONLY_OWNER,
            (commandNodes() + SUB_NODES) - grantedNodes(),
            "the set of nodes no group holds has changed. A node that appeared needs either a grant in permission_default.yaml or an entry in ONLY_OWNER saying why only the owner should have it; a node that disappeared was granted, so drop it from ONLY_OWNER"
        )
    }
}
