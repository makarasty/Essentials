package essential.core.ui

import arc.util.Strings
import arc.util.io.Writes
import essential.common.bundle.Bundle
import essential.common.command.CommandRegistry
import essential.common.database.data.PlayerData
import essential.common.permission.Permission
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.ui.Menus
import mindustry.ui.builder.MenuBuilder
import mindustry.ui.builder.MenuResult
import mindustry.ui.builder.UiBuilder.NodeBuilder
import mindustry.ui.builder.UiBuilder.TableBuilder
import mindustry.ui.builder.UiBuilder.button
import mindustry.ui.builder.UiBuilder.field
import mindustry.ui.builder.UiBuilder.label
import mindustry.ui.builder.UiBuilder.pane
import mindustry.ui.builder.UiBuilder.table
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * `/help` as one menuBuilder dialog: tab row, search field and a scroll pane with a row per command.
 * The engine never unregisters a builder listener and routes any id from any client, so there is ONE
 * process-wide, and who is shown what lives in [sessions]: a per-show random token, one entry per
 * player. A result without a session, with another token (a stale cancel after a re-show, a forged
 * packet) or from another player is dropped.
 */
object HelpMenu {
    private const val LIST = "list"
    private const val QUERY = "q"
    private const val QUERY_MAX = 40

    /** Bytes of command rows per packet: the engine caps a whole packet at 32768 uncompressed, the header takes about a kilobyte. */
    internal const val ROWS_BUDGET = 27_000

    /** Rows budget in use; a test seam so the "refine your search" branch can be reached with few rows. */
    @Volatile internal var rowsBudget = ROWS_BUDGET

    /** Minimum time between two tab or search presses of one session; a test seam. */
    @Volatile internal var pressGapMs = 250L

    private val TABS = listOf("all", "player", "admin", "other")

    private class Entry(val name: String, val params: String, val desc: String, val tab: String)

    private class Session(val owner: PlayerData, val rows: List<Entry>, val tabs: List<String>) {
        @Volatile var token = 0L
        @Volatile var tab = "all"
        @Volatile var query = ""
        @Volatile var lastPress = Long.MIN_VALUE / 2
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    private val id: Int by lazy { Menus.registerMenuBuilder { p, r -> onResult(p, r) } }

    private fun allOptional(params: String) = params.split(' ').filter { it.isNotEmpty() }.all { it.startsWith("[") }

    /** `[` starts a colour tag in a label; doubled it prints as itself. */
    internal fun esc(text: String) = text.replace("[", "[[")

    /** Newlines and control characters out, capped: the client's own maxLength is not trusted. */
    internal fun cleanQuery(text: String?) = text.orEmpty().filter { !it.isISOControl() }.trim().take(QUERY_MAX)

    private fun entries(owner: PlayerData): List<Entry> {
        val bundle = owner.bundle
        // The plain player groups (user and the default one, which holds /login), asked through copies of the viewer's data: Permission keeps the loaded groups private.
        val plain = listOf("user", Permission.default).map { owner.copy(uuid = "", permission = it) }
        val declared = CommandRegistry.declaredNames()
        return Vars.netServer.clientCommands.commandList.mapNotNull { command ->
            val name = CommandRegistry.canonical(command.text)
            if (!Permission.check(owner, name)) return@mapNotNull null
            val key = "command.description." + name.lowercase()
            val desc = Strings.stripColors(if (bundle.resource.containsKey(key)) bundle[key] else command.description)
            val tab = when {
                name !in declared -> "other"
                plain.any { runCatching { Permission.check(it, name) }.getOrDefault(false) } -> "player"
                else -> "admin"
            }
            Entry(command.text, command.paramText, desc, tab)
        }
    }

    fun open(owner: PlayerData) {
        val rows = entries(owner)
        val s = Session(owner, rows, TABS.filter { it == "all" || rows.any { e -> e.tab == it } })
        s.token = random.nextLong()
        sessions[owner.uuid] = s
        val b = owner.bundle
        val tabRow = table()
        s.tabs.forEach { tabRow.add(button(b["command.helpmenu.tab.$it"]).group("tab").checked(it == "all").clicked("tab:$it").growX()) }
        val root = table()
            .add(tabRow).growX().row()
            .add(
                table()
                    .add(field("").id(QUERY).hint(b["command.helpmenu.search"]).maxLength(QUERY_MAX).enter("search").growX())
                    .add(button(b["command.helpmenu.go"]).clicked("search"))
            ).growX().row()
            .add(pane().id(LIST).add(list(s, b)).grow()).grow()
        MenuBuilder.of(root).id(id).token(s.token).title(b["command.page"]).hideOnClick(false)
            .show(owner.player.self())
    }

    private fun size(node: NodeBuilder<*>): Int {
        val out = ByteArrayOutputStream()
        node.write(Writes(DataOutputStream(out)))
        return out.size()
    }

    private fun row(e: Entry, b: Bundle): TableBuilder = table().background("button").margin(6f)
        .add(label("[orange]/${e.name}[] [lightgray]${esc(e.params)}").wrap().growX().labelAlign("left")).growX().row()
        .add(label(esc(e.desc)).wrap().growX().labelAlign("left")).growX().row()
        .add(
            table()
                .add(button(b["command.helpmenu.run"]).clicked("run:${e.name}").growX())
                .add(button(b["command.helpmenu.copy"]).clicked("copy:${e.name}").growX())
        ).growX()

    private fun list(s: Session, b: Bundle): TableBuilder {
        val q = s.query.lowercase()
        val matches = s.rows.filter { (s.tab == "all" || it.tab == s.tab) && (q.isEmpty() || q in it.name.lowercase() || q in it.desc.lowercase()) }
        val list = table()
        var used = 0
        var shown = 0
        for (e in matches) {
            val row = row(e, b)
            used += size(row)
            if (used > rowsBudget) break
            list.add(row).growX().padBottom(4f).row()
            shown++
        }
        if (matches.isEmpty()) list.add(label(b["command.helpmenu.none", esc(s.query)]).wrap().growX())
        else if (shown < matches.size) list.add(label(b["command.helpmenu.more", shown, matches.size]).wrap().growX())
        return list
    }

    private fun onResult(p: Player, r: MenuResult) {
        val uuid = p.uuid()
        val s = sessions[uuid] ?: return
        if (r.token != s.token) return
        val action = r.result
        if (action == null) {
            sessions.remove(uuid, s)
            return
        }
        // Only the search field sits at the top level, so only a tab or search press carries it.
        if (action == "search" || action.startsWith("tab:")) {
            // The tab comes from the server's own list, never raw. It is kept even inside the gap: the client's
            // button group already shows it, so the next accepted press must use it.
            if (action.startsWith("tab:")) s.tab = action.removePrefix("tab:").takeIf { it in s.tabs } ?: return
            // A press inside the gap only skips the rebuild, the session stays.
            val now = System.nanoTime() / 1_000_000
            if (now - s.lastPress < pressGapMs) return
            s.lastPress = now
            s.query = cleanQuery(runCatching { r.getString(QUERY, s.query) }.getOrDefault(s.query))
            Call.menuBuilderUpdate(p.con, id, LIST, list(s, s.owner.bundle))
            return
        }
        // Run and Copy name a command: only one this dialog listed.
        val entry = s.rows.firstOrNull { action == "run:${it.name}" || action == "copy:${it.name}" } ?: return
        if (action.startsWith("copy:")) {
            copy(p, "/${entry.name} ")
            s.owner.send("command.helpmenu.copied")
            return
        }
        // Hiding the dialog sends a cancel with this token: the session goes first so it is dropped.
        sessions.remove(uuid, s)
        Call.hideMenuBuilder(p.con, id)
        if (entry.params.isEmpty()) {
            runAsPlayer(p, entry.name)
        } else {
            // The whole line has to fit the 150-char chat limit, so the name and its space come off.
            TextInput.ask(
                p, "/${entry.name}", s.owner.bundle["command.helpmenu.input", entry.params],
                Vars.maxTextLength - entry.name.length - 2, allowEmpty = allOptional(entry.params)
            ) { player, input ->
                val command = Vars.netServer.clientCommands.commandList.find { it.text == entry.name }
                if (command != null && (input.isNotEmpty() || allOptional(command.paramText))) {
                    runAsPlayer(player, "${entry.name} $input".trim())
                }
            }
        }
    }

    /** Called from the PlayerLeave handler. */
    fun forget(uuid: String) {
        sessions.remove(uuid)
    }
}
