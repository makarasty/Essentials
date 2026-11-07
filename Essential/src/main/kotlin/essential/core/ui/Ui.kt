package essential.core.ui

import arc.util.CommandHandler
import arc.util.Log
import essential.common.database.data.PlayerData
import essential.common.command.CommandRegistry
import essential.common.players
import essential.core.Commands
import essential.core.OwnedMenus
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.gen.Playerc
import mindustry.net.Administration
import mindustry.net.Packets.KickReason
import mindustry.ui.Menus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

const val PICKER_PAGE_SIZE = 8

/** Yes / Cancel. [onYes] runs for Yes only; Cancel and dismissing do nothing. */
object Confirm {
    fun ask(owner: PlayerData, title: String, message: String, onYes: (Player) -> Unit) {
        val bundle = owner.bundle
        // OwnedMenus keeps the listener, and a modified client can send Yes again: run onYes once per show.
        val fired = AtomicBoolean(false)
        val id = OwnedMenus.register(owner) { p, select -> if (select == 0 && fired.compareAndSet(false, true)) onYes(p) }
        OwnedMenus.show(owner.player.con(), id, title, message, arrayOf(arrayOf(bundle["command.menu.yes"], bundle["command.menu.cancel"])))
    }
}

/** One button per choice, then Close. */
object ChoicePicker {
    fun <V> open(owner: PlayerData, title: String, message: String, choices: List<Pair<String, V>>, onPick: (Player, V) -> Unit) {
        val id = OwnedMenus.register(owner) { p, select -> choices.getOrNull(select)?.let { onPick(p, it.second) } }
        val rows = choices.map { arrayOf(it.first) } + listOf(arrayOf(owner.bundle["command.menu.close"]))
        OwnedMenus.show(owner.player.con(), id, title, message, rows.toTypedArray())
    }
}

/** Online players that pass [filter], paged. The pick is checked again: they may have left while the menu was open. */
object PlayerPicker {
    fun open(
        owner: PlayerData, title: String, message: String = "",
        filter: (PlayerData) -> Boolean = { true }, onPick: (PlayerData) -> Unit
    ) {
        PagedMenu(
            owner, title, players.filter(filter), PICKER_PAGE_SIZE,
            closeLabel = owner.bundle["command.menu.close"], fullNav = true,
            header = { _, _ -> message }, label = { it.player.plainName() }
        ) { _, picked ->
            val still = players.firstOrNull { it.uuid == picked.uuid }
            if (still == null) owner.err(Commands.PLAYER_NOT_FOUND) else onPick(still)
        }
    }
}

/**
 * Free text from a player. The engine never unregisters a listener, so there is ONE process-wide, and
 * who is being asked what lives in [pending], one entry per player.
 */
object TextInput {
    private class Pending(val limit: Int, val onResult: (Player, String) -> Unit)

    private val pending = ConcurrentHashMap<String, Pending>()

    private val id: Int by lazy {
        Menus.registerTextInput { p, text ->
            // No pending entry means this player was never asked: a client can send any id.
            val ask = pending.remove(p.uuid()) ?: return@registerTextInput
            val input = sanitize(text, ask.limit) ?: return@registerTextInput
            ask.onResult(p, input)
        }
    }

    /** Newlines out, trimmed; null when nothing came or it is over [limit]: the client's own maxLength is not trusted. */
    internal fun sanitize(text: String?, limit: Int): String? {
        val clean = text?.replace("\n", "")?.replace("\r", "") ?: return null
        return if (clean.length > limit) null else clean.trim()
    }

    fun ask(
        player: Player, title: String, message: String, limit: Int,
        default: String = "", numeric: Boolean = false, allowEmpty: Boolean = false, onResult: (Player, String) -> Unit
    ) {
        pending[player.uuid()] = Pending(limit, onResult)
        Call.textInput(player.con(), id, title, message, limit, default, numeric, allowEmpty)
    }

    /** Called from the PlayerLeave handler. */
    fun forget(uuid: String) {
        pending.remove(uuid)
    }
}

fun copy(player: Playerc, text: String) = Call.copyToClipboard(player.con(), text)

/**
 * Runs [line] (no prefix) as [player] through the normal pipeline, so permission checks apply and errors
 * are reported as for typed input. The one path for anything a menu does for a player: it repeats what
 * `NetClient.sendChatMessage` does before `handleMessage` - the chat rate limit (same numbers, same
 * kick and blacklist), the text limit, newline removal and the command log - and, like it for a
 * command, fires no PlayerChatEvent.
 */
fun runAsPlayer(player: Player, line: String) {
    val con = player.con()
    if (con != null && !con.chatRate.allow(2000, Administration.Config.chatSpamLimit.num())) {
        con.kick(KickReason.kick)
        con.blacklist()
        return
    }
    val handler = Vars.netServer.clientCommands
    val message = (handler.prefix + line).replace("\n", "")
    // Vanilla throws a ValidateException here; a menu has nobody to throw it at, so drop it.
    if (message.length > Vars.maxTextLength) return
    if (Administration.Config.logCommands.bool()) {
        Log.info("<&fi@: @&fr>", "&lk" + player.plainName(), "&lw" + message)
    }
    val response = handler.handleMessage(message, player)
    if (response.type != CommandHandler.ResponseType.valid) {
        Vars.netServer.invalidHandler.handle(player, response)?.let { player.sendMessage(it) }
    }
}

/**
 * A command typed without its player: pick one of the players online (not yourself unless [includeSelf]),
 * then [onPick], by default the same command again with the pick as `#entityId` through [runAsPlayer], so
 * its own permission and outrank checks run unchanged.
 */
fun pickPlayerFor(
    owner: PlayerData, command: String, includeSelf: Boolean = false, filter: (PlayerData) -> Boolean = { true },
    onPick: (PlayerData) -> Unit = { runAsPlayer(owner.player.self(), "${CommandRegistry.registered(command)} #${it.entityId}") }
) {
    val allowed = { it: PlayerData -> (includeSelf || it.uuid != owner.uuid) && filter(it) }
    if (players.none(allowed)) return owner.err(Commands.PLAYER_NOT_FOUND)
    PlayerPicker.open(owner, owner.bundle["command.menu.pick.player"], "/$command", allowed, onPick)
}

/** Free text for the last argument of [command]: runs `command who text` as the player; [titleKey] is the bundle key of the prompt. */
fun askTextFor(owner: PlayerData, command: String, who: String, titleKey: String) {
    TextInput.ask(owner.player.self(), owner.bundle[titleKey], "/$command", 120) { p, text ->
        runAsPlayer(p, "${CommandRegistry.registered(command)} $who $text")
    }
}
