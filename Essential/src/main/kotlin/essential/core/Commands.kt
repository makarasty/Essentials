package essential.core

import arc.Core
import arc.Events
import arc.graphics.Color
import arc.graphics.Colors
import arc.math.Mathf
import arc.util.*
import arc.util.Timer
import com.github.lalyos.jfiglet.FigletFont
import essential.*
import essential.common.*
import essential.common.bundle.Bundle
import essential.common.command.CommandRegistry
import essential.common.database.WorldHistoryBuffer
import essential.common.database.data.*
import essential.common.database.data.plugin.WarpCount
import essential.common.database.data.plugin.WarpTotal
import essential.common.database.table.AchievementTable
import essential.common.database.table.PlayerTable
import essential.common.event.CustomEvents
import essential.common.log.LogType
import essential.common.log.writeLog
import essential.common.permission.Permission
import essential.common.util.PlayerLookup
import essential.common.util.currentTime
import essential.common.util.findPlayerData
import essential.core.Main.Companion.conf
import essential.core.Main.Companion.scope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import ksp.command.ClientCommand
import ksp.command.ServerCommand
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Weathers
import mindustry.core.GameState
import mindustry.game.EventType.GameOverEvent
import mindustry.game.EventType.WaveEvent
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import mindustry.maps.Map
import mindustry.net.NetConnection
import mindustry.net.Packets
import mindustry.net.WorldReloader
import mindustry.type.Item
import mindustry.type.UnitType
import mindustry.ui.Menus
import mindustry.world.Tile
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.mindrot.jbcrypt.BCrypt
import java.util.MissingResourceException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime


/**
 * Menu ids for menus that act on behalf of one player.
 *
 * A menu id is an index into `Menus.menuListeners`, one process-wide list, and `menuChoose` is a
 * remote any client may call with any id - the engine hands every id it receives straight to the
 * listener registered under it. So a menu that acts for the player it was opened for has to check
 * the responder itself; nothing under `Menus` does it.
 *
 * `Menus.registerMenu` appends to that list and the engine exposes no unregister at all. Registering
 * one per menu opened leaked a listener, and the `PlayerData` its closure captured, on every /info,
 * /players, /maps and four more sites, for the life of the server. Sharing a single id per player
 * closed that leak and opened something worse: `Call.menu` shows a *new* dialog every time
 * (`UI.showMenu`), and answering one hides only that one (`UI.lambda$showMenu$27` is
 * `cb.get(opt); dialog.hide()`), so dialogs stack on the client. A dialog left unanswered and later
 * revealed then drove whatever that player had registered most recently, with its own option
 * indices - and index 0 is "ban" on every confirm menu, "close" on /info's and "<-" on the paging
 * ones, so it is the most natural click in the interface.
 *
 * So each slot registers its engine listener exactly **once**, and a slot is handed out again only
 * when nothing can still answer on it. That is knowable rather than guessed: a plain menu dialog
 * leaves the client's screen in exactly two ways, and both report it to the server.
 *
 *  - An option click, which sends `menuChoose(id, option)` and then hides that dialog.
 *  - Escape or back, which arc's `Dialog.closeOnBack` turns into `menuChoose(id, -1)` and a hide.
 *    The engine range-checks the id and not the option, so -1 reaches the listener; every listener
 *    here falls through its `when`, which is why that has always been harmless.
 *
 * `Slot.open` counts the dialogs shown on a slot and [dispatch] retires one per click, so the pool
 * grows to the high-water mark of *concurrently open* owned dialogs and then stops. No id is ever
 * reused while a dialog can still answer on it, which is the whole of the constraint.
 *
 * Accepted: a linear scan over that list under one lock. Everything here is main-thread in
 * production - `menuChoose` arrives through `ArcNetProvider$3.received` -> `Core.app.post` - so the
 * lock is uncontended and the list is only as long as the dialogs open right now. An id-keyed map
 * and per-slot locking if either ever stops being true.
 */
internal object OwnedMenus {
    private class Slot(val id: Int) {
        var owner: String? = null
        var listener: ((Player, Int) -> kotlin.Unit)? = null

        /** Dialogs shown on this id that the client has not answered or dismissed yet. */
        var open = 0

        /** Allocation order, so a caller can name the slot a block took. */
        var seq = 0L
    }

    private val slots = ArrayList<Slot>()
    private var allocations = 0L

    /** Allocations so far. Snapshot it, run something, then ask [idsAllocatedAfter]. */
    val allocationCount: Long
        get() = synchronized(slots) { allocations }

    /**
     * The ids [register] handed out after allocation number [after], oldest first. This is how a test
     * names the menu a command just opened: counting `Menus.menuListeners` cannot do it any more,
     * because a recycled slot registers nothing with the engine at all.
     */
    fun idsAllocatedAfter(after: Long): List<Int> = synchronized(slots) {
        slots.filter { it.seq > after }.sortedBy { it.seq }.map { it.id }
    }

    /** Claims a slot for [owner] and returns the menu id to show it under. */
    fun register(owner: PlayerData, listener: (Player, Int) -> kotlin.Unit): Int = synchronized(slots) {
        // Free first; then a slot whose owner is no longer online, because their dialogs went with
        // their connection and the owner check in dispatch refuses anything that somehow survived.
        // Without that second branch a player who opens a menu and quits without answering it pins
        // its id for the life of the server, which is the original leak again by a slower route.
        // Only then a new one, so the pool settles at the high-water mark and stops growing.
        val slot = slots.firstOrNull { it.open == 0 }
            ?: slots.firstOrNull { s -> players.none { it.uuid == s.owner } }
            ?: newSlot()
        slot.owner = owner.uuid
        slot.listener = listener
        // Unconditional, and load-bearing for the branch above: a slot arrives here either already
        // at zero or carrying dialogs that died with a connection that is gone. Leaving a departed
        // player's count on it would keep that slot permanently ineligible for the first branch, so
        // every menu the next player opened would be handed the same recycled id - the shared-id
        // defect back again, by way of the fix for the leak.
        slot.open = 0
        slot.seq = ++allocations
        slot.id
    }

    /**
     * Shows a menu on an owned id. Every `Call.menu` on one goes through here, because the paging
     * menus re-show on their own id from inside their own listener without re-registering: a slot
     * that counted registrations rather than shows would be handed away with a live dialog on it.
     */
    fun show(con: NetConnection?, id: Int, title: String, message: String, options: Array<Array<String>>) {
        synchronized(slots) { slots.firstOrNull { it.id == id }?.let { it.open++ } }
        Call.menu(con, id, title, message, options)
    }

    private fun newSlot(): Slot {
        // The engine's id and this list's index are different numbers - menuListeners carries every
        // other menu in the process too - so the listener closes over the index and looks the slot up.
        val index = slots.size
        val slot = Slot(Menus.registerMenu { player, option -> dispatch(index, player, option) })
        slots.add(slot)
        return slot
    }

    private fun dispatch(index: Int, player: Player, option: Int) {
        val slot = synchronized(slots) { slots[index] }
        val listener = slot.listener ?: return
        // The responder is whoever called the remote, not whoever the menu was opened for. This is
        // the only gate on that, and it is what makes a recycled slot inert for everybody else.
        if (player.uuid() != slot.owner) return
        try {
            listener(player, option)
        } finally {
            // After the listener, never before: a paging menu re-shows on this same id from inside
            // its own listener, and retiring the click first would free a slot that is about to
            // carry a live dialog again.
            synchronized(slots) { if (slot.open > 0) slot.open-- }
        }
    }
}

class Commands {
    companion object {
        const val PLAYER_NOT_FOUND = "player.not.found"
        const val PLAYER_NOT_REGISTERED = "player.not.registered"
        val charsPlacing = ConcurrentHashMap<String, Array<String>>()

        /**
         * An admin's explicit /nextmap pick, so the popularity tally that runs after every later vote
         * (including someone else's) re-affirms it instead of silently recomputing over it. Reset
         * whenever a vote is cast into an empty mapVotes, which is how a fresh voting round is detected
         * without needing CoreEvent.kt's game-over handler (which clears mapVotes) to know about this.
         */
        private var nextMapAdminOverride: Map? = null

        /**
         * History rows are keyed by tile coordinates and by nothing else, so across a map change they
         * become claims about a map that is no longer loaded and a rollback rebuilds and removes real
         * blocks from them. The game over handler already clears them; a map changed directly never
         * fires one. The flush first is so that rows still sitting in the buffer cannot land after the
         * table has been emptied.
         */
        private fun discardWorldHistory() {
            scope.launch {
                WorldHistoryBuffer.flush()
                clearWorldHistory()
            }
        }

        /**
         * `/info` on yourself has no actions on it, so its listener is a no-op and one id serves
         * every player forever. Registering a fresh one per call only grew `Menus.menuListeners`,
         * which the engine never prunes.
         */
        private val selfInfoMenu: Int by lazy { Menus.registerMenu { _, _ -> } }

        /**
         * Calculate the Levenshtein distance between two strings
         */
        private fun levenshteinDistance(s1: String, s2: String): Int {
            val m = s1.length
            val n = s2.length
            val dp = Array(m + 1) { IntArray(n + 1) }

            for (i in 0..m) {
                dp[i][0] = i
            }

            for (j in 0..n) {
                dp[0][j] = j
            }

            for (i in 1..m) {
                for (j in 1..n) {
                    dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                        dp[i - 1][j - 1]
                    } else {
                        minOf(dp[i - 1][j - 1], minOf(dp[i][j - 1], dp[i - 1][j])) + 1
                    }
                }
            }

            return dp[m][n]
        }
    }

    @ClientCommand("changemap", "<name> [gamemode]", "Change the world or game mode immediately.")
    fun changeMap(playerData: PlayerData, arg: Array<out String>) {
        val arr = HashMap<Int, Map>()
        Vars.maps.all().sortedBy { a -> a.name() }.forEachIndexed { index, map ->
            arr[index] = map
        }

        val map: Map? = if (arg[0].toIntOrNull() != null) {
            arr[arg[0].toInt()]
        } else {
            Vars.maps.all().find { e -> e.name().contains(arg[0], true) }
        }

        if (map != null) {
            try {
                val mode = if (arg.size != 1) {
                    Gamemode.valueOf(arg[1])
                } else {
                    Vars.state.rules.mode()
                }

                val reloader = WorldReloader()
                reloader.begin()
                Vars.world.loadMap(map, map.applyRules(mode))
                Vars.state.rules = Vars.state.map.applyRules(mode)
                Vars.logic.play()
                reloader.end()
                discardWorldHistory()
            } catch (_: IllegalArgumentException) {
                playerData.err("command.changeMap.mode.not.found", arg[1])
            }
        } else {
            playerData.err("command.changeMap.map.not.found", arg[0])
        }
    }

    @ClientCommand("changename", "<target> <new_name>", "Change player name")
    fun changeName(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            val data = PlayerLookup.offline(arg[0], playerData) ?: return@launch
            val self = data.uuid == playerData.uuid
            val online = data.player.con() != null
            if (!self && !online && !arg[0].equals(data.uuid, true)) {
                playerData.err("command.changeName.offline", PlayerLookup.shortName(data.name))
                return@launch
            }

            val exists = suspendTransaction {
                PlayerTable.select(PlayerTable.name)
                    .where { PlayerTable.name eq arg[1] }
                    .firstOrNull()
            }
            if (exists != null) {
                playerData.err("command.changeName.exists", arg[1])
                return@launch
            }

            val previous = data.name
            data.name = arg[1]
            data.update()

            Core.app.post {
                Events.fire(CustomEvents.PlayerNameChanged(previous, arg[1], data.uuid))
                if (online) data.player.name(arg[1])
                if (self) {
                    playerData.send("command.changeName.apply")
                } else if (online) {
                    data.send("command.changeName.apply.other", previous, arg[1])
                }
                if (online) data.send("command.changeName.success", arg[1])
                else playerData.send("command.changeName.success", arg[1])
            }
        }
    }

    @ClientCommand("changepw", "<new_password> <password_repeat>", "Change account password.")
    fun changePassword(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            if (arg[0] != arg[1]) {
                playerData.err("command.changePw.same")
                return@launch
            }

            val password = BCrypt.hashpw(arg[0], BCrypt.gensalt())
            if (!ModuleRuntime.isPasswordAuthenticationEnabled()) {
                playerData.accountID = playerData.name
            }
            playerData.accountPW = password
            playerData.update()
            playerData.send("command.changePw.apply")
        }
    }

    @ClientCommand("chat", "<on/off>", "Mute all players without admins")
    fun chat(playerData: PlayerData, arg: Array<out String>) {
        isGlobalMute = arg[0].equals("off", true)
        if (isGlobalMute) {
            playerData.send("command.chat.off")
        } else {
            playerData.send("command.chat.on")
        }
    }

    @ServerCommand("chat", "<on/off>", "Mute all players without admins")
    fun chat(arg: Array<out String>) {
        isGlobalMute = arg[0].equals("off", true)
        val bundle = Bundle()
        if (isGlobalMute) {
            Log.info(bundle["command.chat.off"])
        } else {
            Log.info(bundle["command.chat.on"])
        }
    }

    @ClientCommand("chars", "<text...>", "Make pixel texts on ground.")
    fun chars(playerData: PlayerData, arg: Array<out String>) {
        if (Vars.world != null) {
            fun convert(text: String): Array<String>? {
                return try {
                    val art =
                        FigletFont.convertOneLine(Main::class.java.classLoader.getResourceAsStream("6x10.flf"), text)
                    art.split("\n").toTypedArray()
                } catch (_: ArrayIndexOutOfBoundsException) {
                    null
                }
            }

            val text = convert(arg[0])
            if (text != null) {
                charsPlacing[playerData.uuid] = text
                playerData.status["chars_text"] = arg[0]
                playerData.send("command.char.select.position")
            } else {
                playerData.err("command.char.unsupported")
            }
        }
    }

    @ClientCommand(name = "color", description = "Enable color nickname")
    fun color(playerData: PlayerData) {
        playerData.animatedName = !playerData.animatedName
    }

    @ClientCommand("dps", description = "Create damage per seconds meter block")
    fun dps(playerData: PlayerData) {
        val currentTile = playerData.player.tileOn()
        val currentUnit = playerData.player.unit()

        if (dpsTile == null) {
            Call.constructFinish(
                currentTile,
                Blocks.thoriumWallLarge,
                currentUnit,
                0,
                Vars.state.rules.waveTeam,
                null
            )
            dpsTile = currentTile
            playerData.send("command.dps.created")
        } else {
            Call.deconstructFinish(dpsTile, Blocks.air, currentUnit)
            dpsTile = null
            playerData.send("command.dps.deleted")
        }
    }

    @ClientCommand(
        "effect",
        "<on/off/level> [color]",
        "Turn other players' effects on or off, or set effects and colors for each level."
    )
    fun effect(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            when {
                arg[0].toUShortOrNull() != null -> {
                    if (arg[0].toInt() <= playerData.level) {
                        playerData.effectLevel = arg[0].toShortOrNull()
                        if (arg.size == 2) {
                            try {
                                if (Colors.get(arg[1]) == null) {
                                    Color.valueOf(arg[1])
                                }

                                playerData.effectColor = arg[1]
                            } catch (_: IllegalArgumentException) {
                                playerData.err("command.effect.no.color")
                            } catch (_: StringIndexOutOfBoundsException) {
                                playerData.err("command.effect.no.color")
                            }
                        }
                        playerData.update()
                    } else {
                        playerData.err("command.effect.level")
                    }
                }

                arg[0] == "off" -> {
                    playerData.effectVisibility = false
                    playerData.update()
                    playerData.send("command.effect.off")
                }

                arg[0] == "on" -> {
                    playerData.effectVisibility = true
                    playerData.update()
                    playerData.send("command.effect.on")
                }

                else -> {
                    playerData.err("command.effect.invalid")
                }
            }
        }
    }

    @ClientCommand("exp", "<set/hide/add/remove> [values/player] [player]", "Edit account exp values")
    fun exp(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            suspend fun set(exp: Int?, type: String) {
                suspend fun set(data: PlayerData) {
                    val previous = data.exp
                    when (type) {
                        "set" -> data.exp = arg[1].toInt()
                        "add" -> data.exp += arg[1].toInt()
                        "remove" -> data.exp -= arg[1].toInt()
                    }
                    data.update()
                    playerData.send("command.exp.result", previous, data.exp)
                }

                if (exp != null) {
                    if (arg.size == 3) {
                        set(PlayerLookup.offline(arg[2], playerData) ?: return)
                    } else {
                        set(playerData)
                    }
                } else {
                    playerData.err("command.exp.invalid")
                }
            }

            when (arg[0]) {
                "set" -> {
                    if (arg.size >= 2) {
                        set(arg[1].toIntOrNull(), "set")
                    } else {
                        playerData.err("command.exp.invalid")
                    }
                }

                "hide" -> {
                    if (arg.size == 2) {
                        val other = PlayerLookup.offline(arg[1], playerData) ?: return@launch
                        other.hideRanking = !other.hideRanking
                        // Awaited, not detached. The enclosing body is already a coroutine - which is why
                        // the self branch below can await - so scope.launch bought nothing and sent the
                        // confirmation whether or not the row was written. /ranking filters on the row, so
                        // a failed write left the admin told the opposite of what they would then see.
                        other.update()
                        val msg = if (other.hideRanking) "hide" else "unhide"
                        playerData.send("command.exp.ranking.$msg")
                        return@launch
                    }

                    playerData.hideRanking = !playerData.hideRanking
                    playerData.update()
                    val msg = if (playerData.hideRanking) "hide" else "unhide"
                    playerData.send("command.exp.ranking.$msg")
                }

                "add" -> {
                    if (arg.size >= 2) {
                        set(arg[1].toIntOrNull(), "add")
                    } else {
                        playerData.err("command.exp.invalid")
                    }
                }

                "remove" -> {
                    if (arg.size >= 2) {
                        set(arg[1].toIntOrNull(), "remove")
                    } else {
                        playerData.err("command.exp.invalid")
                    }
                }

                else -> {
                    playerData.err("command.exp.invalid.command")
                }
            }
        }
    }

    @ClientCommand("fillitems", "[team]", "Fill the core with items.")
    fun fillItems(playerData: PlayerData, arg: Array<out String>) {
        val player = playerData.player

        if (arg.isEmpty()) {
            if (Vars.state.teams.cores(player.team()).isEmpty) {
                playerData.err("command.fillItems.core.empty")
                return
            }

            Vars.content.items().forEach {
                Vars.state.teams.cores(player.team()).first().items[it] =
                    Vars.state.teams.cores(player.team()).first().storageCapacity
            }
            playerData.send("command.fillItems.core.filled", player.team().coloredName())
        } else {
            val team = selectTeam(arg[0])
            if (Vars.state.teams.cores(team).isEmpty) {
                playerData.err("command.fillItems.core.empty")
                return
            }

            Vars.content.items().forEach {
                Vars.state.teams.cores(team).forEach { core ->
                    core.items[it] = core.storageCapacity
                }
            }

            playerData.send("command.fillItems.core.filled", team.coloredName())
        }
    }

    @ClientCommand("gg", "[team]", "Make game over immediately.")
    fun gg(playerData: PlayerData, arg: Array<out String>) {
        if (arg.isEmpty()) {
            Events.fire(GameOverEvent(Vars.state.rules.waveTeam))
        } else {
            Events.fire(GameOverEvent(selectTeam(arg[0])))
        }
    }

    @ClientCommand("god", "[player]", "Set max player health")
    fun god(playerData: PlayerData) {
        playerData.player.unit()?.health(1.0E8f)
        playerData.send("command.god")
    }

    @ClientCommand("help", "[page]", "Show command lists")
    fun help(playerData: PlayerData, arg: Array<out String>) {
        if (arg.isNotEmpty() && !Strings.canParseInt(arg[0])) {
            val key = "command.help.${arg[0]}"
            if (playerData.bundle.resource.containsKey(key)) {
                playerData.send(key)
            } else {
                playerData.err("command.help.not.exists")
            }
            return
        }

        val temp = ArrayList<String>()
        for (a in 0 until Vars.netServer.clientCommands.commandList.size) {
            val command = Vars.netServer.clientCommands.commandList[a]
            val name = CommandRegistry.canonical(command.text)
            if (Permission.check(playerData, name)) {
                val key = "command.description." + name.lowercase()
                val description = if (playerData.bundle.resource.containsKey(key)) {
                    playerData.bundle[key]
                } else {
                    command.description
                }
                temp.add("[orange] /${command.text} [white]${command.paramText} [lightgray]- $description\n")
            }
        }
        val result = StringBuilder()
        val per = 8
        var page = if (arg.isNotEmpty()) abs(Strings.parseInt(arg[0])) else 1
        val pages = Mathf.ceil(temp.size.toFloat() / per)
        page--

        if (page !in 0..<pages) {
            playerData.err("command.page.range", pages)
            return
        }

        result.append("[orange]-- ${playerData.bundle["command.page"]}[lightgray] ${page + 1}[gray]/[lightgray]${pages}[orange] --\n")
        for (a in per * page until (per * (page + 1)).coerceAtMost(temp.size)) {
            result.append(temp[a])
        }

        val msg = result.toString().substring(0, result.length - 1)
        playerData.sendDirect(msg)
    }

    @OptIn(ExperimentalTime::class)
    @ClientCommand("info", "[player...]", "Show player info")
    fun info(playerData: PlayerData, arg: Array<out String>) {
        val bundle = playerData.bundle
        val timeBundleFormat = "command.info.time"

        fun timeFormat(seconds: Int, msg: String): String {
            val days = seconds / (24 * 60 * 60)
            val hours = (seconds % (24 * 60 * 60)) / (60 * 60)
            val minutes = ((seconds % (24 * 60 * 60)) % (60 * 60)) / 60
            val remainingSeconds = ((seconds % (24 * 60 * 60)) % (60 * 60)) % 60

            return when (msg) {
                timeBundleFormat -> bundle[timeBundleFormat, days, hours, minutes, remainingSeconds]
                "$timeBundleFormat.minimal" -> bundle["$timeBundleFormat.minimal", hours, minutes, remainingSeconds]
                else -> ""
            }
        }

        // todo 코드 정리
        fun show(target: PlayerData): String {
            return """
                ${bundle["command.info.name"]}: ${target.name}[white]
                ${bundle["command.info.placeCount"]}: ${target.blockPlaceCount}
                ${bundle["command.info.breakCount"]}: ${target.blockBreakCount}
                ${bundle["command.info.level"]}: ${target.level}
                ${bundle["command.info.exp"]}: ${Exp[target]}
                ${bundle["command.info.joinDate"]}: ${
                target.firstPlayed.format(LocalDateTime.Formats.ISO)
            }
                ${bundle["command.info.playtime"]}: ${timeFormat(target.totalPlayed, timeBundleFormat)}
                ${bundle["command.info.playtime.current"]}: ${
                timeFormat(
                    target.currentPlayTime,
                    "$timeBundleFormat.minimal"
                )
            }
                ${bundle["command.info.attackClear"]}: ${target.attackClear}
                ${bundle["command.info.waveClear"]}: ${target.waveClear}
                ${bundle["command.info.pvpWinRate"]}: [green]${target.pvpWinCount}[white]/[scarlet]${target.pvpLoseCount}[white]([sky]${
                if (target.pvpWinCount + target.pvpLoseCount != 0) round(
                    target.pvpWinCount.toDouble() / (target.pvpWinCount + target.pvpLoseCount) * 100
                ) else 0
            }%[white])
                ${bundle["command.info.joinStacks"]}: ${target.attendanceDays}
                ${bundle["command.info.discord"]}: ${if (target.discordID != null) bundle["command.info.discord.verified"] else bundle["command.info.discord.none"]}
                """.trimIndent()
        }

        val lineBreak = "\n"
        val close = "info.button.close"
        val ban = "info.button.ban"
        val cancel = "info.button.cancel"

        if (arg.isEmpty()) {
            Call.menu(playerData.player.con(), selfInfoMenu, bundle["info.title"], show(playerData), arrayOf(arrayOf(bundle[close])))
        } else if (Permission.check(playerData, "info.other")) {
            var targetData: PlayerData? = null
            var isBanned = false

            fun unbanPlayer(data: PlayerData?) {
                if (data != null) {
                    val name = data.name
                    val ip = Vars.netServer.admins.getInfo(data.uuid).lastIP

                    if (!Vars.netServer.admins.unbanPlayerID(data.uuid)) {
                        if (!Vars.netServer.admins.unbanPlayerIP(ip)) {
                            playerData.err(PLAYER_NOT_FOUND)
                        } else {
                            playerData.send("command.unban.ip", ip)
                        }
                    } else {
                        playerData.send("command.unban.id", data.uuid)
                    }

                    writeLog(LogType.Player, Bundle()["log.player.unbanned", name, ip])
                }
            }

            val controlMenus = arrayOf(
                arrayOf(bundle[close]),
                arrayOf(bundle[ban], bundle["info.button.kick"])
            )

            val unbanControlMenus = arrayOf(
                arrayOf(bundle[close]),
                arrayOf(bundle["info.button.unban"], bundle["info.button.kick"])
            )

            val offlineControlMenus = arrayOf(
                arrayOf(bundle[close]),
                arrayOf(bundle[ban])
            )

            val offlineUnbanControlMenus = arrayOf(
                arrayOf(bundle[close]),
                arrayOf(bundle["info.button.unban"])
            )

            val banMenus = arrayOf(
                arrayOf(
                    bundle["info.button.tempBan.10min"],
                    bundle["info.button.tempBan.1hour"],
                    bundle["info.button.tempBan.1day"]
                ),
                arrayOf(
                    bundle["info.button.tempBan.1week"],
                    bundle["info.button.tempBan.2week"],
                    bundle["info.button.tempBan.1month"]
                ),
                arrayOf(bundle["info.button.tempBan.permanent"]),
                arrayOf(bundle[close])
            )

            val mainMenu = OwnedMenus.register(playerData) { p, select ->
                when (select) {
                    1 if !isBanned -> {
                        val innerMenu = OwnedMenus.register(playerData) { _, s ->
                            val time: Int = when (s) {
                                0 -> 10
                                1 -> 60
                                2 -> 1440
                                3 -> 10080
                                4 -> 20160
                                5 -> 43800
                                6 -> -1
                                else -> 0
                            }

                            try {
                                val timeText = bundle["info.button.tempban.${
                                    when (s) {
                                        0 -> "10min"
                                        1 -> "1hour"
                                        2 -> "1day"
                                        3 -> "1week"
                                        4 -> "2week"
                                        5 -> "1month"
                                        6 -> "permanent"
                                        else -> ""
                                    }
                                }"]

                                if (s <= 5) {
                                    val tempBanConfirmMenu = OwnedMenus.register(playerData) { _, i ->
                                        if (i == 0) {
                                            require(targetData != null) {
                                                "DB error?"
                                            }
                                            targetData!!.banExpireDate =
                                                Clock.System.now().plus(time.minutes).toLocalDateTime(systemTimezone)
                                            // The ban itself is applied below regardless (banPlayerID does
                                            // not depend on this row), so a failed write here is a durability
                                            // problem, not a "nothing happened" one - the admin is told rather
                                            // than the confirm silently going through. Captured now: targetData
                                            // is a mutable var that a later /info call can repoint before this
                                            // coroutine's Core.app.post runs.
                                            val bannedTarget = targetData!!
                                            scope.launch {
                                                if (!bannedTarget.update()) {
                                                    Core.app.post { playerData.err("command.tempBan.db.failed", bannedTarget.name) }
                                                }
                                            }
                                            Events.fire(
                                                CustomEvents.PlayerTempBanned(
                                                    targetData!!.name,
                                                    p.plainName(),
                                                    Clock.System.now().plus(time.minutes).toString()
                                                )
                                            )
                                            val uuid = targetData!!.uuid
                                            val label = Undo.label(uuid)
                                            // As in the server /tempban path: banPlayerID returns false when the
                                            // target was already banned and did nothing, so an undo entry that
                                            // reverts via Undo.unban must not be recorded here — it would fully
                                            // lift a ban that predates this menu action.
                                            val freshBan = Vars.netServer.admins.banPlayerID(uuid)
                                            if (targetData!!.player.con() != null) {
                                                targetData!!.player.kick(bundle["command.tempBan.banned", targetData!!.name, p.plainName(), targetData!!.banExpireDate.toString()])
                                            }
                                            if (freshBan) {
                                                Undo.record(playerData, "tempban", uuid, label) { Undo.unban(it, false) }
                                            } else {
                                                playerData.send(
                                                    "command.tempBan.already.banned",
                                                    targetData!!.name,
                                                    targetData!!.banExpireDate.toString()
                                                )
                                            }
                                        }
                                    }
                                    OwnedMenus.show(
                                        p.con(),
                                        tempBanConfirmMenu,
                                        bundle["info.tempBan.title"],
                                        bundle["info.tempBan.confirm", timeText] + lineBreak,
                                        arrayOf(arrayOf(bundle[ban], bundle[cancel]))
                                    )
                                } else if (s == 6) {
                                    val banConfirmMenu = OwnedMenus.register(playerData) { _, i ->
                                        if (i == 0) {
                                            val uuid = targetData!!.uuid
                                            val label = Undo.label(uuid)
                                            val ipBanned = Undo.ban(uuid)
                                            Undo.record(playerData, "ban", uuid, label) { Undo.unban(it, ipBanned) }
                                        }
                                    }
                                    // 영구 차단
                                    OwnedMenus.show(
                                        p.con(),
                                        banConfirmMenu,
                                        bundle["info.ban.title"],
                                        bundle["info.ban.confirm"] + lineBreak,
                                        arrayOf(arrayOf(bundle[ban], bundle[cancel]))
                                    )
                                }
                            } catch (_: MissingResourceException) {
                            }
                        }
                        OwnedMenus.show(
                            p.con(),
                            innerMenu,
                            bundle["info.tempBan.title"],
                            bundle["info.tempBan.confirm"] + lineBreak,
                            banMenus
                        )
                    }

                    1 -> {
                        val unbanConfirmMenu = OwnedMenus.register(playerData) { _, i ->
                            if (i == 0) {
                                targetData!!.banExpireDate = null
                                // Captured now: targetData is a mutable var a later /info call can repoint
                                // before this coroutine's Core.app.post runs. The unban itself (below) does
                                // not depend on this write succeeding; only its durability does.
                                val unbannedTarget = targetData!!
                                scope.launch {
                                    if (!unbannedTarget.update()) {
                                        Core.app.post { playerData.err("command.unban.db.failed", unbannedTarget.name) }
                                    }
                                }
                                unbanPlayer(targetData)
                                Events.fire(CustomEvents.PlayerUnbanned(targetData!!.name, currentTime()))
                                playerData.send("log.player.unbanned", targetData!!.name, targetData!!.uuid)
                                val uuid = targetData!!.uuid
                                Undo.record(
                                    playerData, "unban", uuid, Undo.label(uuid), "command.undo.button.banAgain"
                                ) { Undo.ban(it) }
                            }
                        }
                        OwnedMenus.show(
                            p.con(),
                            unbanConfirmMenu,
                            bundle["info.unban.title"],
                            bundle["info.unban.confirm", targetData!!.name] + lineBreak,
                            arrayOf(arrayOf(bundle["info.button.unban"], bundle[cancel]))
                        )
                    }

                    2 -> {
                        if (targetData != null && targetData!!.player.con() != null) {
                            val uuid = targetData!!.uuid
                            val label = Undo.label(uuid)
                            targetData!!.player.kick(Packets.KickReason.kick)
                            Undo.record(
                                playerData, "kick", uuid, label,
                                alternativeKey = "command.undo.button.ban", alternative = { Undo.ban(it) }
                            ) { Undo.liftKick(it) }
                        }
                    }
                }
            }

            fun open(other: PlayerData) {
                val info = Vars.netServer.admins.getInfo(other.uuid)
                isBanned = Vars.netServer.admins.isIDBanned(other.uuid) || Vars.netServer.admins.isIPBanned(info.lastIP)
                val banned = "\n${bundle["info.banned"]}: $isBanned"
                val menu = if (Permission.check(other, "info.other")) {
                    arrayOf(arrayOf(bundle[close]))
                } else if (other.player.con() == null) {
                    if (!isBanned) offlineControlMenus else offlineUnbanControlMenus
                } else if (!isBanned) {
                    controlMenus
                } else {
                    unbanControlMenus
                }
                targetData = other
                OwnedMenus.show(
                    playerData.player.con(),
                    mainMenu,
                    bundle["info.admin.title"],
                    show(other) + banned + lineBreak,
                    menu
                )
            }

            val online = (PlayerLookup.findOnline(arg[0]) as? PlayerLookup.Result.Found)?.value
            val current = online?.let { target -> players.find { it.uuid == target.uuid() } }
            if (current != null) {
                open(current)
            } else {
                scope.launch {
                    val other = PlayerLookup.offline(arg[0], playerData) ?: return@launch
                    Core.app.post { open(other) }
                }
            }
        } else {
            playerData.err("command.permission.false")
        }
    }

    @ClientCommand("js", "[code...]", "Execute JavaScript code")
    fun js(playerData: PlayerData, arg: Array<out String>) {
        if (arg.isEmpty()) {
            playerData.err("command.js.invalid")
        } else {
            Vars.mods.scripts.runConsole(arg[0]).also { result ->
                try {
                    val errorName: String = result.take(result.indexOf(' ') - 1)
                    Class.forName("org.mozilla.javascript.$errorName")
                    playerData.sendDirect("[scarlet]> $result")
                } catch (_: Throwable) {
                    playerData.sendDirect("> $result")
                }
            }
        }
    }

    @ClientCommand("kickall", description = "Kick all players without admins.")
    fun kickAll(playerData: PlayerData, arg: Array<out String>) {
        Groups.player.toList().forEach { player ->
            if (player.uuid() == playerData.uuid) return@forEach
            val target = findPlayerData(player.uuid())
            if (target == null || !Permission.check(target, "kick.admin")) {
                player.kick(Packets.KickReason.kick)
                player.remove()
            }
        }
        playerData.send("command.kickAll.done")
    }

    @ServerCommand("kickall", description = "Kick all players.")
    fun kickAll() {
        Groups.player.toList().forEach {
            if (!it.admin) it.kick(Packets.KickReason.kick)
        }
        Log.info(Bundle()["command.kickAll.done"])
    }

    @ClientCommand("kill", "[player]", "Kill player's unit.")
    fun kill(playerData: PlayerData, arg: Array<out String>) {
        if (arg.isEmpty()) {
            playerData.player.unit()?.kill()
            playerData.send("command.kill.self")
        } else {
            if (Permission.check(playerData, "kill.other")) {
                val other = PlayerLookup.online(arg[0], playerData)
                if (other != null) {
                    val unit = other.unit()
                    if (unit != null) {
                        unit.kill()
                        playerData.send("command.kill.done", other.plainName())
                    } else {
                        playerData.err("command.kill.no.unit", other.plainName())
                    }
                }
            } else {
                playerData.send("command.permission.false")
            }
        }
    }

    @ServerCommand("kill", "<player>", "Kill player's unit")
    fun kill(arg: Array<out String>) {
        val other = PlayerLookup.online(arg[0])
        if (other != null) {
            val unit = other.unit()
            if (unit != null) {
                unit.kill()
                Log.info(Bundle()["command.kill.done", other.plainName()])
            } else {
                Log.warn(Bundle()["command.kill.no.unit", other.plainName()])
            }
        }
    }

    @ClientCommand("killall", "[team]", "Kill all enemy units")
    fun killAll(playerData: PlayerData, arg: Array<out String>) {
        val count: Int
        if (arg.isEmpty()) {
            count = Groups.unit.size()
            repeat(Team.all.count()) {
                Groups.unit.each { u: Unit -> u.kill() }
            }

        } else {
            val team = selectTeam(arg[0])
            count = Groups.unit.filter { u -> u.team == team }.size
            Groups.unit.each { u -> if (u.team == team) u.kill() }
        }
        playerData.send("command.killall.count", count)
    }

    @ServerCommand("killall", "[team]", "Kill all units")
    fun killAll(arg: Array<out String>) {
        val count: Int
        if (arg.isEmpty()) {
            count = Groups.unit.size()
            repeat(Team.all.count()) {
                Groups.unit.each { u: Unit -> u.kill() }
            }
        } else {
            val team = selectTeam(arg[0])
            count = Groups.unit.filter { u -> u.team == team }.size
            Groups.unit.each { u -> if (u.team == team) u.kill() }
        }
        Log.info(Bundle()["command.killall.count", count])
    }

    @ClientCommand("killunit", "<name> [amount] [team]", "Destroy specific units")
    fun killUnit(playerData: PlayerData, arg: Array<out String>) {
        val unit = Vars.content.units().find { unitType: UnitType -> unitType.name == arg[0] }

        fun destroy(team: Team) {
            if (Groups.unit.size() < arg[1].toInt() || arg[1].toInt() == 0) {
                Groups.unit.each { if (it.type() == unit && it.team == team) it.kill() }
            } else {
                var count = 0
                Groups.unit.each {
                    if (it.type() == unit && it.team == team && count != arg[1].toInt()) {
                        it.kill()
                        count++
                    }
                }
            }
        }

        if (unit != null) {
            if (arg.size > 1) {
                if (arg[1].toIntOrNull() != null) {
                    if (arg.size == 3) {
                        val team = selectTeam(arg[2])
                        destroy(team)
                    } else {
                        destroy(playerData.player.team())
                    }
                } else {
                    playerData.err("command.killUnit.invalid.number")
                }
            } else {
                Groups.unit.each { if (it.type() == unit && it.team == playerData.player.team()) it.kill() }
            }
        } else {
            playerData.err("command.killUnit.not.found")
        }
    }

    @ServerCommand("killunit", "<name> [amount] [team]", "Destroy specific units")
    fun killUnit(arg: Array<out String>) {
        val unit = Vars.content.units().find { unitType: UnitType -> unitType.name == arg[0] }
        val bundle = Bundle()

        fun destroy(team: Team?) {
            if (Groups.unit.size() < arg[1].toInt() || arg[1].toInt() == 0) {
                Groups.unit.each { if (it.type() == unit && (team == null || it.team == team)) it.kill() }
            } else {
                // todo 완료시 count 출력
                var count = 0
                Groups.unit.each {
                    if (it.type() == unit && (team == null || it.team == team) && count != arg[1].toInt()) {
                        it.kill()
                        count++
                    }
                }
            }
        }

        if (unit != null) {
            if (arg.size > 1) {
                if (arg[1].toIntOrNull() != null) {
                    if (arg.size == 3) {
                        val team = selectTeam(arg[2])
                        destroy(team)
                    } else {
                        destroy(null)
                    }
                } else {
                    Log.err(bundle["command.killUnit.invalid.number"])
                }
            } else {
                Groups.unit.each { if (it.type() == unit) it.kill() }
            }
        } else {
            Log.err(bundle["command.killUnit.not.found"])
        }
    }

    @ClientCommand("log", description = "Enable block history view mode")
    fun log(playerData: PlayerData) {
        playerData.viewHistoryMode = !playerData.viewHistoryMode
        val msg = if (playerData.viewHistoryMode) {
            "enabled"
        } else {
            "disabled"
        }
        playerData.send("command.log.$msg")
    }

    @ClientCommand("maps", "[page]", "Show server map lists")
    fun maps(playerData: PlayerData) {
        val list = Vars.maps.all().sortedBy { a -> a.name() }
        val bundle = playerData.bundle
        val prebuilt = ArrayList<Pair<String, Array<Array<String>>>>()
        val buffer = Mathf.ceil(list.size.toFloat() / 6)
        val pages = if (buffer > 1.0) buffer - 1 else 0
        val title = bundle["command.page.server"]

        for (page in 0..pages) {
            val build = StringBuilder()
            for (a in 6 * page until (6 * (page + 1)).coerceAtMost(list.size)) {
                build.append("${list[a].name()}\n[orange]${bundle["command.maps.author"]} ${list[a].author()}[white]\n[gray]ID: $a[green]   ${list[a].width}x${list[a].height}[white]\n\n")
            }

            val options = arrayOf(
                arrayOf("<-", bundle["command.maps.page", page, pages], "->"),
                arrayOf(bundle["command.maps.close"])
            )

            prebuilt.add(Pair(build.toString(), options))
        }

        var mainMenu = 0
        var page = 0
        mainMenu = OwnedMenus.register(playerData) { p, select ->
            when (select) {
                0 -> {
                    if (page != 0) page--
                    OwnedMenus.show(p.con(), mainMenu, title, prebuilt[page].first, prebuilt[page].second)
                }

                1 -> {
                    OwnedMenus.show(p.con(), mainMenu, title, prebuilt[page].first, prebuilt[page].second)
                }

                2 -> {
                    if (page != pages) page++
                    OwnedMenus.show(p.con(), mainMenu, title, prebuilt[page].first, prebuilt[page].second)
                }

                else -> {}
            }
        }
        OwnedMenus.show(playerData.player.con(), mainMenu, title, prebuilt[0].first, prebuilt[0].second)
    }

    @ClientCommand("meme", "<type>", "Enjoy mindustry meme features!")
    fun meme(playerData: PlayerData, arg: Array<out String>) {
        when (arg[0]) {
            "router" -> {
                val zero = arrayOf(
                    """
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040][][#404040]
                            """.trimIndent(), """
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][][#404040][]
                            """.trimIndent(), """
                            [stat][#404040][][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat][][stat]
                            """.trimIndent(), """
                            [stat][#404040][][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            """.trimIndent(), """
                            [#404040][stat][][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][stat]
                            [stat][#404040][]
                            [stat][#404040][]
                            """.trimIndent()
                )
                val loop = arrayOf(
                    """
                            [#6B6B6B][stat][#6B6B6B]
                            [stat][#404040][]
                            [stat][#404040]
                            [stat][#404040][]
                            [#404040][]
                            [stat][#404040][]
                            [stat][#404040][]
                            [#6B6B6B][stat][#404040][][#6B6B6B]
                            """.trimIndent(), """
                            [#6B6B6B][stat][#6B6B6B]
                            [#6B6B6B][stat][#404040][][#6B6B6B]
                            [stat][#404040][]
                            [#404040][]
                            [stat][#404040][]
                            [stat][#404040][]
                            [#6B6B6B][stat][#404040][][#6B6B6B]
                            [#6B6B6B][stat][#6B6B6B]
                            """.trimIndent(), """
                            [#6B6B6B][#585858][stat][][#6B6B6B]
                            [#6B6B6B][#828282][stat][#404040][][][#6B6B6B]
                            [#585858][stat][#404040][][#585858]
                            [stat][#404040][]
                            [stat][#404040][]
                            [#585858][stat][#404040][][#585858]
                            [#6B6B6B][stat][#404040][][#828282][#6B6B6B]
                            [#6B6B6B][#585858][stat][][#6B6B6B]
                            """.trimIndent(), """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][stat][][#6B6B6B]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#585858][stat][#404040][][#585858]
                            [#585858][stat][#404040][][#585858]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#6B6B6B][stat][][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """.trimIndent(), """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#585858][#6B6B6B][stat][][#828282][#585858]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#585858][#6B6B6B][stat][#404040][][#828282][#585858]
                            [#585858][#6B6B6B][stat][][#828282][#585858]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """.trimIndent(), """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][stat][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][stat][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """.trimIndent(), """
                            [#6B6B6B][#585858][#6B6B6B]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#6B6B6B][#828282][#585858]
                            [#585858][#6B6B6B][#828282][#585858]
                            [#6B6B6B][#828282][#6B6B6B]
                            [#6B6B6B][#585858][#6B6B6B]
                            """.trimIndent()
                )
                if (playerData.status.containsKey("router")) {
                    playerData.status.remove("router")
                } else {
                    scope.launch {
                        suspend fun change(name: String): Boolean {
                            if (!playerData.status.containsKey("router")) return false
                            playerData.player.name(name)
                            delay(500.milliseconds)
                            return playerData.status.containsKey("router")
                        }

                        playerData.status["router"] = "true"
                        try {
                            while (playerData.player.unit() != null && !playerData.player.unit().dead() && playerData.status.containsKey("router")) {
                                var active = true
                                for (name in loop) {
                                    if (!change(name)) {
                                        active = false
                                        break
                                    }
                                }
                                if (!active) break

                                delay(5000.milliseconds)
                                if (!playerData.status.containsKey("router")) break

                                for (name in loop.reversed()) {
                                    if (!change(name)) {
                                        active = false
                                        break
                                    }
                                }
                                if (!active) break

                                for (name in zero) {
                                    if (!change(name)) {
                                        active = false
                                        break
                                    }
                                }
                                if (!active) break
                            }
                        } finally {
                            val permission = Permission[playerData]
                            val targetName = if (permission.name.isNotEmpty() && permission.name != playerData.player.name()) {
                                permission.name
                            } else {
                                playerData.name
                            }
                            playerData.player.name(targetName)
                        }
                    }
                }
            }
            else -> {
                playerData.err("command.meme.not.found")
            }
        }
    }

    @ClientCommand("motd", description = "Show server's message of the day")
    fun motd(playerData: PlayerData) {
        val player = playerData.player
        val motd = readMotd(player.locale()).orEmpty()
        if (motd.isNotEmpty()) {
            val count = motd.lines().size
            if (count > 10) Call.infoMessage(player.con(), motd) else player.sendMessage(motd)
        } else {
            playerData.send("command.motd.not-found")
        }
    }

    @ClientCommand("mute", "<player>", "Mute player")
    fun mute(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            val target = PlayerLookup.offline(arg[0], playerData) ?: return@launch
            target.chatMuted = true
            target.update()
            playerData.send("command.mute", target.name)
            Undo.record(playerData, "mute", target.uuid, Undo.label(target.uuid)) { Undo.mute(it, false) }
        }
    }

    @ServerCommand("mute", "<player>", "Mute player")
    fun mute(arg: Array<out String>) {
        val bundle = Bundle()
        scope.launch {
            val target = PlayerLookup.offline(arg[0]) ?: return@launch
            target.chatMuted = true
            target.update()
            Log.info(bundle["command.mute", target.name])
            Undo.record(null, "mute", target.uuid, Undo.label(target.uuid)) { Undo.mute(it, false) }
        }
    }

    @ClientCommand("pause", description = "Pause or Unpause map")
    fun pause(playerData: PlayerData) {
        if (Vars.state.isPaused) {
            Vars.state.set(GameState.State.playing)
            playerData.send("command.pause.unpaused")
        } else {
            Vars.state.set(GameState.State.paused)
            playerData.send("command.pause.paused")
        }
    }

    @ClientCommand("players", "[page]", "Show current players list")
    fun players(playerData: PlayerData) {
        val bundle = playerData.bundle
        val prebuilt = ArrayList<Pair<String, Array<Array<String>>>>()
        val buffer = Mathf.ceil(players.size.toFloat() / 6)
        val pages = if (buffer > 1.0) buffer - 1 else 0
        val title = bundle["command.page.server"]
        val showUuid = Permission.check(playerData, "info.other")

        for (page in 0..pages) {
            val build = StringBuilder()
            for (a in 6 * page until (6 * (page + 1)).coerceAtMost(players.size)) {
                val data = players[a]
                val uuid = if (showUuid) " [gray]${data.uuid.take(8)}[]" else ""
                build.append("[gray]${data.entityId}[] ${PlayerLookup.shortName(data.player.plainName())}$uuid\n")
            }

            val options = arrayOf(
                arrayOf("<-", bundle["command.players.page", page, pages], "->"),
                arrayOf(bundle["command.players.close"])
            )

            prebuilt.add(Pair(build.toString(), options))
        }

        var mainMenu = 0
        var page = 0
        mainMenu = OwnedMenus.register(playerData) { p, select ->
            when (select) {
                0 -> {
                    if (page != 0) page--
                    OwnedMenus.show(p.con(), mainMenu, title, prebuilt[page].first, prebuilt[page].second)
                }

                1 -> {
                    OwnedMenus.show(p.con(), mainMenu, title, prebuilt[page].first, prebuilt[page].second)
                }

                2 -> {
                    if (page != pages) page++
                    OwnedMenus.show(p.con(), mainMenu, title, prebuilt[page].first, prebuilt[page].second)
                }

                else -> {}
            }
        }
        OwnedMenus.show(playerData.player.con(), mainMenu, title, prebuilt[0].first, prebuilt[0].second)
    }

    @ClientCommand("ranking", "<time/exp/attack/place/break/pvp> [page]", "Show player ranking")
    fun ranking(playerData: PlayerData, arg: Array<out String>) {
        val bundle = playerData.bundle
        val player = playerData.player

        scope.launch {
            try {
                fun timeFormat(seconds: Long): String {
                    val days = seconds / (24 * 60 * 60)
                    val hours = (seconds % (24 * 60 * 60)) / (60 * 60)
                    val minutes = ((seconds % (24 * 60 * 60)) % (60 * 60)) / 60
                    val remainingSeconds = ((seconds % (24 * 60 * 60)) % (60 * 60)) % 60

                    return bundle["command.info.time", days, hours, minutes, remainingSeconds]
                }

                val firstMessage = when (arg[0].lowercase()) {
                    "time" -> "command.ranking.time"
                    "exp" -> "command.ranking.exp"
                    "attack" -> "command.ranking.attack"
                    "place" -> "command.ranking.place"
                    "break" -> "command.ranking.break"
                    "pvp" -> "command.ranking.pvp"
                    else -> null
                }

                if (firstMessage == null) {
                    playerData.err("command.ranking.wrong")
                    return@launch
                }

                Core.app.post { playerData.send("command.ranking.wait") }
                val time = mutableMapOf<Pair<String, String>, Int>()
                val exp = mutableMapOf<Pair<String, String>, Int>()
                val attack = mutableMapOf<Pair<String, String>, Int>()
                val placeBlock = mutableMapOf<Pair<String, String>, Int>()
                val breakBlock = mutableMapOf<Pair<String, String>, Int>()
                val pvp = mutableMapOf<Pair<String, String>, Triple<Short, Short, Short>>()

                suspendTransaction {
                    if (arg[0].lowercase() == "pvp") {
                        PlayerTable.select(
                            PlayerTable.name,
                            PlayerTable.uuid,
                            PlayerTable.hideRanking,
                            PlayerTable.pvpWinCount,
                            PlayerTable.pvpLoseCount,
                            PlayerTable.pvpEliminatedCount
                        ).collect {
                            if (!it[PlayerTable.hideRanking]) {
                                pvp[Pair(it[PlayerTable.name], it[PlayerTable.uuid])] = Triple(
                                    it[PlayerTable.pvpWinCount],
                                    it[PlayerTable.pvpLoseCount],
                                    it[PlayerTable.pvpEliminatedCount]
                                )
                            }
                        }
                    } else {
                        val type = when (arg[0].lowercase()) {
                            "time" -> PlayerTable.totalPlayed
                            "exp" -> PlayerTable.exp
                            "attack" -> PlayerTable.attackClear
                            "place" -> PlayerTable.blockPlaceCount
                            "break" -> PlayerTable.blockBreakCount
                            else -> PlayerTable.uuid // dummy
                        }
                        PlayerTable.select(PlayerTable.name, PlayerTable.uuid, PlayerTable.hideRanking, type).collect {
                            if (!it[PlayerTable.hideRanking]) {
                                when (arg[0].lowercase()) {
                                    "time" -> time[Pair(it[PlayerTable.name], it[PlayerTable.uuid])] =
                                        it[PlayerTable.totalPlayed]

                                    "exp" -> exp[Pair(it[PlayerTable.name], it[PlayerTable.uuid])] = it[PlayerTable.exp]
                                    "attack" -> attack[Pair(it[PlayerTable.name], it[PlayerTable.uuid])] =
                                        it[PlayerTable.attackClear]

                                    "place" -> placeBlock[Pair(it[PlayerTable.name], it[PlayerTable.uuid])] =
                                        it[PlayerTable.blockPlaceCount]

                                    "break" -> breakBlock[Pair(it[PlayerTable.name], it[PlayerTable.uuid])] =
                                        it[PlayerTable.blockBreakCount]
                                }
                            }
                        }
                    }
                }

                val d = when (arg[0].lowercase()) {
                    "time" -> time.toList().sortedWith(compareBy { -it.second })
                    "exp" -> exp.toList().sortedWith(compareBy { -it.second })
                    "attack" -> attack.toList().sortedWith(compareBy { -it.second })
                    "place" -> placeBlock.toList().sortedWith(compareBy { -it.second })
                    "break" -> breakBlock.toList().sortedWith(compareBy { -it.second })
                    "pvp" -> pvp.toList().sortedWith(compareBy { -it.second.first })
                    else -> {
                        return@launch
                    }
                }

                val string = StringBuilder()
                val per = 8
                var page = if (arg.size == 2) abs(Strings.parseInt(arg[1])) else 1
                val pages = Mathf.ceil(d.size.toFloat() / per)
                page--

                if (page !in 0..<pages) {
                    Core.app.post { playerData.err("command.page.range", pages) }
                    return@launch
                }
                string.append(bundle[firstMessage, page + 1, pages] + "\n")

                for (a in per * page until (per * (page + 1)).coerceAtMost(d.size)) {
                    if (arg[0].lowercase() == "pvp") {
                        val rank = d[a].second as Triple<*, *, *>
                        val win = (rank.first as Short).toInt()
                        val defeat = (rank.second as Short).toInt()
                        val elimination = (rank.third as Short).toInt()
                        val rate = round((win.toFloat() / (defeat.toFloat() + elimination.toFloat())) * 100)
                        string.append("[white]$a[] ${d[a].first.first}[white] [yellow]-[] [green]$win${bundle["command.ranking.pvp.win"]}[] / [scarlet]$defeat${bundle["command.ranking.pvp.lose"]}[] ($rate%)\n")
                    } else {
                        val text = if (arg[0].lowercase() == "time") {
                            timeFormat(d[a].second.toString().toLong())
                        } else if (arg[0].lowercase() == "exp") {
                            "Lv.${Exp.calculateLevel(d[a].second as Int)} - ${d[a].second}"
                        } else {
                            d[a].second
                        }
                        string.append("[white]${a + 1}[] ${d[a].first.first}[white] [yellow]-[] $text\n")
                    }
                }
                string.substring(0, string.length - 1)
                if (!playerData.hideRanking) {
                    string.append("[purple]=======================================[]\n")
                    for (a in d.indices) {
                        if (d[a].first.second == player.uuid()) {
                            if (d[a].second is HashMap<*, *>) {
                                val rank = d[a].second as HashMap<*, *>
                                val rate = round(
                                    (rank.keys.first().toString().toFloat() / (rank.keys.first().toString()
                                        .toFloat() + rank.keys.first().toString().toFloat())) * 100
                                )
                                string.append("[white]${a + 1}[] ${d[a].first.first}[white] [yellow]-[] [green]${rank.keys.first()}${bundle["command.ranking.pvp.win"]}[] / [scarlet]${rank.values.first()}${bundle["command.ranking.pvp.lose"]}[] ($rate%)")
                            } else {
                                val text = if (arg[0].lowercase() == "time") {
                                    timeFormat(d[a].second.toString().toLong())
                                } else if (arg[0].lowercase() == "exp") {
                                    "Lv.${Exp.calculateLevel(d[a].second as Int)} - ${d[a].second}"
                                } else {
                                    d[a].second
                                }
                                string.append("[white]${a + 1}[] ${d[a].first.first}[white] [yellow]-[] $text")
                            }
                        }
                    }
                }

                Core.app.post {
                    playerData.sendDirect(string.toString())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Core.app.post { playerData.err("command.ranking.wrong") }
            }
        }
    }

    /**
     * Rebuilds a config value from its stored string against the classes [block] itself declares
     * accepting (`Block.configurations`), instead of guessing a type from the string. Returns null when
     * the declared type cannot be round-tripped through a bare string (a Point2 link, a live Building
     * reference, or any class this does not know how to rebuild) so the caller can refuse the restore
     * rather than hand the block a value of the wrong type.
     */
    private fun reconstructConfig(block: mindustry.world.Block, raw: String): Any? {
        val configClasses = mutableListOf<Class<*>>()
        block.configurations.each { configClass, _ -> configClasses += configClass }

        for (configClass in configClasses) {
            val value = when {
                configClass == java.lang.Boolean::class.java -> raw.toBooleanStrictOrNull()
                configClass == java.lang.Integer::class.java -> raw.toIntOrNull()
                configClass == String::class.java -> raw
                mindustry.ctype.MappableContent::class.java.isAssignableFrom(configClass) ->
                    Vars.content.byName(raw)?.takeIf { configClass.isInstance(it) }

                else -> null
            }
            if (value != null) return value
        }
        return null
    }

    @ClientCommand("rollback", "<player>", "Undo all actions taken by the player.")
    fun rollback(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            try {
                WorldHistoryBuffer.flush()
                val history = getAllWorldHistory()

                Core.app.post {
                    try {
                        var affectedCount = 0
                        val unrestoredConfigs = mutableListOf<String>()
                        val grouped = history.groupBy { Pair(it.x.toInt(), it.y.toInt()) }

                        grouped.forEach { (pos, entriesUnsorted) ->
                            // Exact, not a substring: entries.player is a player-chosen display name, and
                            // a substring match reverted bystanders too - "Bobby" matched a rollback of
                            // "Bob", and renaming to contain someone else's name could redirect blame.
                            // Stripped of color markup: "place"/"break" store the raw name
                            // (CoreEvent.kt's TileLog construction uses target.name, not plainName()),
                            // and a colored or group-recolored name would otherwise never match a plain
                            // admin-typed arg[0] at all, turning the command into a silent no-op.
                            // This narrows the match; it does not close it, because the stored name is a
                            // snapshot, not a uuid, so two entries can still share one exact name if a
                            // later player renamed to a name an earlier one already had. See ask/9b-*.md.
                            val hasPlayerAction = entriesUnsorted.any { Strings.stripColors(it.player).equals(arg[0], ignoreCase = true) }
                            if (!hasPlayerAction) return@forEach

                            val entries = entriesUnsorted.sortedBy { it.time }

                            val firstIdx = entries.indexOfFirst { Strings.stripColors(it.player).equals(arg[0], ignoreCase = true) }
                            if (firstIdx == -1) return@forEach

                            val targetTile = Vars.world.tile(pos.first, pos.second) ?: return@forEach

                            var desiredBlockName: String? = null // null -> air
                            var desiredTeam: Team = Team.derelict
                            var desiredRot = 0

                            fun applyPrevOccupancyFrom(indexExclusive: Int) {
                                for (i in indexExclusive downTo 0) {
                                    val e = entries[i]
                                    when (e.action) {
                                        "place" -> {
                                            desiredBlockName = e.tile
                                            desiredTeam = Team.all.find { t -> t.name == e.team } ?: Team.derelict
                                            desiredRot = e.rotate
                                            return
                                        }

                                        "break" -> {
                                            desiredBlockName = null // air
                                            desiredTeam = Team.derelict
                                            desiredRot = 0
                                            return
                                        }
                                    }
                                }
                            }

                            if (firstIdx > 0) {
                                applyPrevOccupancyFrom(firstIdx - 1)
                            } else {
                                val first = entries[firstIdx]
                                when (first.action) {
                                    "place" -> {
                                        desiredBlockName = null
                                    }

                                    "break" -> {
                                        desiredBlockName = first.tile
                                        desiredTeam = Team.all.find { t -> t.name == first.team } ?: Team.derelict
                                        desiredRot = first.rotate
                                    }

                                    else -> {
                                        desiredBlockName = targetTile.block().name.takeIf { it != Blocks.air.name }
                                        desiredTeam = targetTile.team()
                                        desiredRot = targetTile.build?.rotation ?: 0
                                    }
                                }
                            }

                            var desiredConfig: String? = null
                            for (i in (firstIdx - 1) downTo 0) {
                                val e = entries[i]
                                if (e.value != null) {
                                    desiredConfig = e.value
                                    break
                                }
                            }

                            if (desiredBlockName == null || desiredBlockName == Blocks.air.name) {
                                targetTile.remove()
                            } else {
                                val block = Vars.content.block(desiredBlockName)
                                if (block != null) {
                                    targetTile.setBlock(block, desiredTeam, desiredRot)
                                    if (desiredConfig != null && targetTile.build != null) {
                                        // The stored value is a flattened string (its original type is
                                        // lost before this ever reaches Commands.kt - see ask/9-1.md), so
                                        // reconstruct it against what the block itself declares it accepts
                                        // rather than guessing a type from the string alone. A block that
                                        // accepts an Item/Liquid/Block/UnitType round-trips through
                                        // Vars.content.byName, since MappableContent.toString() is exactly
                                        // that name. Anything the block declares that isn't one of the
                                        // simple types below (a Point2 link, a live Building reference)
                                        // cannot be reconstructed from a bare string; refuse rather than
                                        // hand the block a value of the wrong type.
                                        val configValue = reconstructConfig(block, desiredConfig)
                                        if (configValue != null) {
                                            targetTile.build.configure(configValue)
                                        } else {
                                            unrestoredConfigs += block.name
                                        }
                                    }
                                } else {
                                    targetTile.remove()
                                }
                            }
                            affectedCount++
                        }

                        for (p in Groups.player) {
                            Call.worldDataBegin(p.con)
                            Vars.netServer.sendWorldData(p)
                        }

                        playerData.send("command.rollback.success", arg[0], affectedCount)
                        if (unrestoredConfigs.isNotEmpty()) {
                            playerData.send(
                                "command.rollback.config.unrestored",
                                unrestoredConfigs.size,
                                unrestoredConfigs.distinct().joinToString(", ")
                            )
                        }
                    } catch (e: Exception) {
                        playerData.err("command.rollback.failed")
                        Log.err("Failed to roll back the actions of ${arg[0]}", e)
                    }
                }
            } catch (e: Exception) {
                Core.app.post { playerData.err("command.rollback.failed") }
                Log.err("Failed to roll back the actions of ${arg[0]}", e)
            }
        }
    }

    @ClientCommand("hub", "<parameter> [ip] [parameters...]", "Create a server to server point.")
    fun hub(playerData: PlayerData, arg: Array<out String>) {
        val type = arg[0]
        val x = playerData.player.tileX()
        val y = playerData.player.tileY()
        val name = Vars.state.map.name()
        var ip = ""
        var port = 6567
        if (arg.size > 1) {
            if (arg[1].contains(":")) {
                val address = arg[1].split(":").toTypedArray()
                ip = address[0]

                if (address[1].toIntOrNull() == null) {
                    playerData.err("command.hub.address.port.invalid")
                    return
                }
                port = address[1].toInt()
            } else {
                ip = arg[1]
            }
        }

        scope.launch {
            when (type) {
                "set" -> {
                    if (pluginData.hubMapName == null) {
                        pluginData.hubMapName = Vars.state.map.name()
                        playerData.send("command.hub.mode.on")
                    } else if (pluginData.hubMapName != Vars.state.map.name()) {
                        // hubMapName is one value shared by every server (task-124, unfixed - see
                        // ask/9b-1.md): naming it here at least tells the admin which map to look for,
                        // and that if it is not one of this server's own, another server set it.
                        playerData.err("command.hub.mode.exists.at", pluginData.hubMapName ?: "?")
                    } else {
                        pluginData.hubMapName = null
                        playerData.send("command.hub.mode.off")
                    }
                }

                "zone" -> {
                    if (!playerData.status.containsKey("hub_first") && !playerData.status.containsKey("hub_second")) {
                        if (ip.isEmpty()) {
                            playerData.err("command.hub.address.invalid")
                        } else {
                            playerData.status["hub_ip"] = ip
                            playerData.status["hub_port"] = port.toString()
                            playerData.status["hub_first"] = "true"
                            playerData.send("command.hub.zone.first")
                        }
                    } else {
                        playerData.send("command.hub.zone.process")
                    }
                }

                "block" -> if (arg.size != 3) {
                    playerData.err("command.hub.block.parameter")
                } else {
                    if (ip.isEmpty()) {
                        playerData.err("command.hub.address.invalid")
                    } else {
                        playerData.status["hub_block_ip"] = ip
                        playerData.status["hub_block_port"] = port.toString()
                        playerData.status["hub_block_desc"] = arg[2]
                        playerData.status["hub_block_selecting"] = "true"
                        playerData.send("command.hub.block.select")
                    }
                }

                "count" -> {
                    if (arg.size < 2) {
                        playerData.err("command.hub.count.parameter")
                    } else {
                        if (ip.isEmpty()) {
                            playerData.err("command.hub.address.invalid")
                        } else {
                            pluginData.data.warpCount.add(WarpCount(name, Vars.world.tile(x, y).pos(), ip, port))
                            playerData.send("command.hub.count", "$x:$y", arg[1])
                        }
                    }
                }

                "total" -> {
                    pluginData.data.warpTotal.add(WarpTotal(name, Vars.world.tile(x, y).pos(), 0, 1))
                    playerData.send("command.hub.total", "$x:$y")
                }

                "remove" -> {
                    if (ip.isEmpty()) {
                        playerData.err("command.hub.address.invalid")
                    } else {
                        pluginData.data.warpBlock.removeAll { a -> a.ip == ip && a.port == port }
                        pluginData.data.warpZone.removeAll { a -> a.ip == ip && a.port == port }
                        playerData.send("command.hub.removed", arg[1])
                    }
                }

                "reset" -> {
                    pluginData.data.warpTotal.clear()
                    pluginData.data.warpCount.clear()
                    playerData.send("command.hub.reset")
                }

                else -> playerData.send("command.hub.help")
            }

            pluginData.update()
        }
    }

    @ClientCommand("setitem", "<item> <amount> [team]", "Set item to team core")
    fun setItem(playerData: PlayerData, arg: Array<out String>) {
        fun set(item: Item) {
            fun s(team: Team) {
                // Team.core() returns null when the team has no core (wiped out, or never had one) -
                // a plain Java platform type Kotlin will not stop you from dereferencing, and this was
                // dereferencing it twice with no check at all: a deterministic NPE.
                val core = team.core()
                if (core == null) {
                    playerData.err("command.setItem.no.core", team.name)
                    return
                }
                core.items[item] = if (core.storageCapacity < arg[1].toInt()) core.storageCapacity else arg[1].toInt()
            }

            val amount = arg[1].toIntOrNull()
            if (amount != null) {
                if (arg.size == 3) {
                    val team = Team.all.find { a -> a.name == arg[2] }
                    if (team != null) {
                        s(team)
                    } else {
                        playerData.err("command.setItem.wrong.team")
                    }
                } else {
                    s(playerData.player.team())
                }
            } else {
                playerData.err("command.setItem.wrong.amount")
            }
        }

        val item = Vars.content.item(arg[0])
        if (item != null) {
            set(item)
        } else if (arg[0].equals("all", false)) {
            Vars.content.items().forEach {
                set(it)
            }
        } else {
            playerData.err("command.setItem.item.not.exists")
        }
    }

    private fun validPermissionGroup(group: String, sender: PlayerData?): Boolean {
        if (Permission.hasGroup(group)) return true
        val list = Permission.groups.joinToString(", ")
        if (sender != null) {
            sender.err("command.setPerm.invalidGroup", group, list)
        } else {
            Log.warn(Bundle()["command.setPerm.invalidGroup", group, list])
        }
        return false
    }

    private fun applyPermissionGroup(data: PlayerData, group: String, sender: PlayerData?) {
        val previous = Permission.groupOf(data.uuid, data.permission)
        val hadUserEntry = Permission.hasUserEntry(data.uuid)

        // permission_user.yaml is per server and wins over the shared permission column, so an entry
        // written here would mask this group on this server and be pushed back over the shared row on
        // the next load. An entry the operator wrote by hand is left in place and kept in step; one
        // this command created is not worth having.
        val written = if (hadUserEntry) {
            Permission.setGroup(data.uuid, group)
        } else {
            Permission.removeUserEntry(data.uuid, group)
        }
        if (!written) {
            val problem = Permission.userFileProblem().orEmpty()
            if (sender != null) {
                sender.err("permission.user.file.invalid", problem)
            } else {
                Log.warn(Bundle()["permission.user.file.invalid", problem])
            }
            return
        }

        data.permission = group
        // The group change is live on this server the instant data.permission is set above - only its
        // durability is in question here. A failed write is not undone (the file half already committed,
        // and reverting the live group would desync this server from what the file now says), but the
        // admin is told, instead of a persistence failure being reported as an unqualified success.
        scope.launch {
            if (!data.update()) {
                Core.app.post {
                    if (sender != null) {
                        sender.err("command.setPerm.db.failed", data.name)
                    } else {
                        Log.warn(Bundle()["command.setPerm.db.failed", data.name])
                    }
                }
            }
        }

        if (sender != null) {
            sender.send("command.setPerm.success", data.name, group)
        } else {
            Log.info(Bundle()["command.setPerm.success", data.name, group])
        }

        Undo.record(sender, "setperm", data.uuid, Undo.label(data.uuid)) {
            Undo.permission(it, previous, hadUserEntry)
        }
    }

    private fun withPermissionTarget(target: String, sender: PlayerData?, action: (PlayerData) -> kotlin.Unit) {
        val online = PlayerLookup.findOnline(target)
        if (online is PlayerLookup.Result.Found) {
            val data = players.find { it.uuid == online.value.uuid() }
            if (data == null || data.temporary) {
                if (sender != null) sender.err(PLAYER_NOT_REGISTERED) else Log.warn(Bundle()[PLAYER_NOT_REGISTERED])
            } else {
                action(data)
            }
            return
        }
        if (PlayerLookup.ambiguous(online, target, sender)) return

        val name = PlayerLookup.shortName(target)
        if (sender != null) sender.send("command.setPerm.queued", name) else Log.info(Bundle()["command.setPerm.queued", name])

        scope.launch {
            val data = (if (sender != null) PlayerLookup.offline(target, sender) else PlayerLookup.offline(target))
                ?: return@launch
            Core.app.post { action(data) }
        }
    }

    @ClientCommand("setperm", "<player> <group>", "Set the player's permission group.")
    fun setPerm(playerData: PlayerData, arg: Array<out String>) {
        if (!validPermissionGroup(arg[1], playerData)) return
        withPermissionTarget(arg[0], playerData) { data -> applyPermissionGroup(data, arg[1], playerData) }
    }

    @ServerCommand("setperm", "<player> <group>", "Set the player's permission group.")
    fun setPerm(arg: Array<out String>) {
        if (!validPermissionGroup(arg[1], null)) return
        withPermissionTarget(arg[0], null) { data -> applyPermissionGroup(data, arg[1], null) }
    }

    private fun printPermission(data: PlayerData) {
        val bundle = Bundle()
        val source = if (Permission.hasUserEntry(data.uuid)) "command.perm.source.file" else "command.perm.source.database"
        Log.info(
            bundle[
                "command.perm.result",
                data.name,
                data.uuid,
                Permission.groupOf(data.uuid, data.permission),
                Permission.isAdmin(data.uuid, data.permission),
                bundle[source]
            ]
        )
    }

    @ServerCommand("perm", "<player>", "Show the player's effective permission group.")
    fun perm(arg: Array<out String>) {
        withPermissionTarget(arg[0], null) { data -> printPermission(data) }
    }

    @ClientCommand("skip", "<wave>", "Start n wave immediately")
    fun skip(playerData: PlayerData, arg: Array<out String>) {
        val wave = arg[0].toIntOrNull()
        if (wave != null) {
            if (wave <= 0) {
                playerData.err("command.skip.number.low")
            } else if (wave > conf.command.skip.adminLimit) {
                // Every wave is spawned before this command returns, on the main thread, so the count the
                // caller picks is how long the server stops ticking and how many units it allocates.
                playerData.err("command.skip.number.high", conf.command.skip.adminLimit)
            } else {
                val previousWave = Vars.state.wave
                repeat(wave) {
                    Vars.spawner.spawnEnemies()
                    Vars.state.wave++
                    Vars.state.wavetime = Vars.state.rules.waveSpacing
                    // task-131/task-074: this advances the wave the same way the game's own timer does,
                    // but never told anything listening for a wave to actually pass - wave-based records
                    // (achievements, stats) silently missed every skipped wave.
                    Events.fire(WaveEvent())
                }
                playerData.send("command.skip.process", previousWave, Vars.state.wave)
            }
        } else {
            playerData.err("command.skip.number.invalid")
        }
    }

    @ClientCommand(
        "spawn",
        "<unit/block> <name> [amount(rotate)/block_team] [unit_team]",
        "Spawn units or block at the player's current location."
    )
    fun spawn(playerData: PlayerData, arg: Array<out String>) {
        val player = playerData.player

        val type = arg[0]
        val name = arg[1]
        val parameter = if (arg.size == 3) {
            arg[2].toIntOrNull() ?: selectTeam(arg[2])
        } else {
            1
        }
        val team = if (arg.size == 4) selectTeam(arg[3]) else player.team()
        val spread = (Vars.tilesize * 1.5).toFloat()

        when {
            type.equals("unit", true) -> {
                val unit = Vars.content.units().find { unitType: UnitType -> unitType.name == name }
                if (unit != null) {
                    if (parameter is Int) {
                        if (!unit.hidden) {
                            // useUnitCap only gates Units.canCreate, which factory blocks (Reconstructor,
                            // UnitAssembler, UnitFactory, UnitCargoLoader) consult before producing a unit.
                            // UnitType.spawn/create - what this command actually calls - never reads it, so
                            // setting it false here bought nothing for this command and left the cap
                            // disabled for that unit type's factories server-wide until restart.
                            isCheated = true
                            repeat(parameter) {
                                Tmp.v1.rnd(spread)
                                unit.spawn(team, player.x + Tmp.v1.x, player.y + Tmp.v1.y)
                            }
                        } else {
                            playerData.err("command.spawn.unit.invalid")
                        }
                    } else {
                        playerData.err("command.spawn.number")
                    }
                } else {
                    playerData.err("command.spawn.invalid")
                }
            }

            type.equals("block", true) -> {
                if (Vars.content.blocks().find { a -> a.name == name } != null) {
                    isCheated = true
                    Call.constructFinish(
                        player.tileOn(),
                        Vars.content.blocks().find { a -> a.name.equals(name, true) },
                        player.unit(),
                        0,
                        team,
                        null
                    )
                } else {
                    playerData.err("command.spawn.invalid")
                }
            }

            else -> {
                return
            }
        }
    }

    @ClientCommand("status", description = "Show current server status")
    fun status(playerData: PlayerData) {
        val bundle = playerData.bundle

        fun longToTime(seconds: Long): String {
            val min = seconds / 60
            val hour = min / 60
            val days = hour / 24
            return String.format("%d:%02d:%02d:%02d", days % 365, hour % 24, min % 60, seconds % 60)
        }

        val message = StringBuilder()
        message.append(
            """
                [#DEA82A]${bundle["command.status.info"]}[]
                [#2B60DE]========================================[]
                ${bundle["command.status.name"]}: ${Vars.state.map.name()}[white]
                ${bundle["command.status.creator"]}: ${Vars.state.map.author()}[white]
                TPS: ${Core.graphics.framesPerSecond}/60
                ${bundle["command.status.banned", Vars.netServer.admins.banned.size]}
                ${bundle["command.status.playtime"]}: $playTime
                ${bundle["command.status.uptime"]}: $uptime
            """.trimIndent()
        )

        if (Vars.state.rules.pvp) {
            message.appendLine()
            message.appendLine(
                """
                    [#2B60DE]========================================[]
                    [#DEA82A]${bundle["command.status.pvp"]}[]
                """.trimIndent()
            )

            fun winPercentage(team: Team): Double {
                var player = arrayOf<Pair<Team, Double>>()
                players.forEach {
                    val rate = it.pvpWinCount.toDouble() / (it.pvpWinCount + it.pvpLoseCount).toDouble()
                    player += Pair(it.player.team(), if (rate.isNaN()) 0.0 else rate)
                }

                val targetTeam = player.filter { it.first == team }
                val rate = targetTeam.map { it.second }
                return rate.average()
            }

            val teamRate = mutableMapOf<Team, Double>()
            var teams = arrayOf<Pair<Team, Int>>()
            for (a in Vars.state.teams.active) {
                val rate: Double = winPercentage(a.team)
                teamRate[a.team] = rate
                teams += Pair(a.team, a.players.size)
            }

            teamRate.forEach {
                message.appendLine("${it.key.coloredName()} : ${round(it.value * 100).toInt()}%")
            }

            playerData.sendDirect(message.toString().dropLast(1))
        } else {
            playerData.sendDirect(message.toString())
        }
    }

    @ClientCommand("strict", "<player>", "Set whether the target player can build or not.")
    fun strict(playerData: PlayerData, arg: Array<out String>) {
        val target = PlayerLookup.onlineData(arg[0], playerData) ?: return
        target.strictMode = !target.strictMode
        scope.launch { target.update() }
        val undo = if (target.strictMode) ".undo" else ""
        playerData.send("command.strict$undo", target.name)
        val previous = !target.strictMode
        Undo.record(playerData, "strict", target.uuid, Undo.label(target.uuid)) { Undo.strict(it, previous) }
    }

    @ServerCommand("strict", "<player>", "Set whether the target player can build or not.")
    fun strict(arg: Array<out String>) {
        val bundle = Bundle()
        val target = PlayerLookup.onlineData(arg[0]) ?: return
        target.strictMode = !target.strictMode
        scope.launch { target.update() }
        val undo = if (target.strictMode) ".undo" else ""
        Log.info(bundle["command.strict$undo", target.name])
        val previous = !target.strictMode
        Undo.record(null, "strict", target.uuid, Undo.label(target.uuid)) { Undo.strict(it, previous) }
    }

    @ClientCommand("t", "<message...>", "Send a meaage only to your teammates.")
    fun t(playerData: PlayerData, arg: Array<out String>) {
        // Team chat went straight to sendMessage, so none of the five registered chat filters saw it:
        // this plugin's mute and global-mute check, the word blacklist, the keyboard-layout rewrite, a
        // running vote, and the engine's own anti-spam. filterMessage is the only thing that runs them,
        // and vanilla's own /t calls it, so this restores what replacing that command had removed.
        // The chatMuted check that used to stand here is the first of those filters and, unlike this
        // command, tells the player why they were refused. A message beginning with "/" is dropped, as
        // it is in public chat.
        val message = Vars.netServer.admins.filterMessage(playerData.player.self(), arg[0]) ?: return
        Groups.player.each({ p -> p.team() === playerData.player.team() }) { o ->
            o.sendMessage("[#" + playerData.player.team().color.toString() + "]<T>[] ${playerData.player.coloredName()} [orange]>[white] $message")
        }
    }

    @ClientCommand("votemap", "<id>", "Start a vote to change to the map with the given ID (see /maps)")
    fun voteMap(playerData: PlayerData, arg: Array<out String>) {
        vote(playerData, arrayOf("map", arg[0], playerData.bundle["command.votemap.reason"]))
    }

    @ClientCommand("rtv", "", "Vote to move on to the next map")
    fun rtv(playerData: PlayerData) {
        Rtv.vote(playerData)
    }

    @ClientCommand("team", "<team> [name]", "Set player team")
    fun team(playerData: PlayerData, arg: Array<out String>) {
        val team = selectTeam(arg[0])

        if (arg.size == 1) {
            playerData.player.team(team)
        } else if (Permission.check(playerData, "team.other")) {
            val other = PlayerLookup.online(arg[1], playerData)
            if (other != null) {
                val previous = other.team()
                other.team(team)
                Undo.record(playerData, "team", other.uuid(), Undo.label(other.uuid())) { Undo.team(it, previous) }
            }
        }
    }

    @ServerCommand("team", "<team> <name>", "Set player team")
    fun team(arg: Array<out String>) {
        val team = selectTeam(arg[0])
        val other = PlayerLookup.online(arg[1])
        if (other != null) {
            val previous = other.team()
            other.team(team)
            Undo.record(null, "team", other.uuid(), Undo.label(other.uuid())) { Undo.team(it, previous) }
        }
    }

    private fun applyTempBan(uuid: String, name: String, expire: LocalDateTime, reason: String?) {
        val bundle = Bundle()
        val message = bundle["command.tempBan.banned", name, "Server", expire.toString()]
        // banPlayerID returns false when the id was already banned (permanently, or by an earlier
        // tempban) and did nothing. The caller still moves the expiry, which is the deliberate part
        // of a re-tempban; what must not happen is treating this as a fresh ban for Undo purposes,
        // because Undo.unban lifts the ban outright and would wipe out a ban that predates this call.
        val freshBan = Vars.netServer.admins.banPlayerID(uuid)
        Groups.player.find { it.uuid() == uuid }?.kick(reason ?: message)
        if (freshBan) {
            Undo.record(null, "tempban", uuid, Undo.label(uuid)) { Undo.unban(it, false) }
            Log.info(message)
        } else {
            Log.warn(bundle["command.tempBan.already.banned", name, expire.toString()])
        }
    }

    // todo tempban client -> server
    @OptIn(ExperimentalTime::class)
    @ServerCommand("tempban", "<player> <time> [reason...]", "Ban the player for aa certain peroid of time")
    fun tempBan(arg: Array<out String>) {
        val bundle = Bundle()
        val minute = arg[1].toIntOrNull()

        if (minute == null) {
            Log.warn(bundle["command.tempBan.not.number"])
            return
        }

        val admins = Vars.netServer.admins
        val expire = Clock.System.now().plus(minute.minutes).toLocalDateTime(systemTimezone)
        val reason = if (arg.size > 2) arg[2] else null
        val online = (PlayerLookup.findOnline(arg[0]) as? PlayerLookup.Result.Found)?.value
        val uuid = online?.uuid() ?: arg[0].takeIf { admins.getInfoOptional(it) != null }

        if (uuid != null) {
            applyTempBan(uuid, online?.plainName() ?: admins.getInfo(uuid).lastName, expire, reason)
            scope.launch { TempBan.setBanExpire(uuid, expire) }
            return
        }

        scope.launch {
            val target = PlayerLookup.offline(arg[0]) ?: return@launch
            // applyTempBan mutates Vars.netServer.admins and kicks a Player; both are engine state the
            // main thread also touches, and this coroutine is not that thread. setBanExpire has no
            // ordering dependency on it (a database write, independent of the in-engine ban), so it is
            // left running here rather than also bounced through Core.app.post.
            Core.app.post { applyTempBan(target.uuid, target.name, expire, reason) }
            TempBan.setBanExpire(target.uuid, expire)
        }
    }

    @OptIn(ExperimentalTime::class)
    @ClientCommand("time", description = "Show current server time")
    fun time(playerData: PlayerData) {
        val now = Clock.System.now().toString()
        playerData.send("command.time", now)
    }

    @ClientCommand("tp", "<player>", "Teleport to other players")
    fun tp(playerData: PlayerData, arg: Array<out String>) {
        val other = PlayerLookup.online(arg[0], playerData) ?: return

        playerData.player.unit()?.x(other.x)
        playerData.player.unit()?.y(other.y)
        Call.setPosition(playerData.player.con(), other.x, other.y)
        Call.setCameraPosition(playerData.player.con(), other.x, other.y)
    }

    @ClientCommand("track", description = "Display the mouse positions of players.")
    fun track(playerData: PlayerData) {
        playerData.mouseTracking = !playerData.mouseTracking
        val msg = if (!playerData.mouseTracking) ".disabled" else ""
        playerData.send("command.track.toggle$msg")
    }

    @ServerCommand("permaban", "<player>", "Make an existing ban permanent by clearing its expiry")
    fun permaban(arg: Array<out String>) {
        val bundle = Bundle()
        scope.launch {
            val found = PlayerLookup.findExact(arg[0])
            if (PlayerLookup.ambiguous(found, arg[0], null)) return@launch
            val uuid = if (found is PlayerLookup.Result.Found) found.value.uuid else arg[0]

            val data = findPlayerData(uuid)?.takeIf { !it.temporary } ?: getPlayerData(uuid)
            val orphan = pluginData.data.tempBans[uuid]
            val previous = data?.banExpireDate
                ?: orphan?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

            if (found !is PlayerLookup.Result.Found && data == null && orphan == null) {
                Log.warn(bundle[PlayerLookup.NOT_FOUND])
                return@launch
            }

            // Only the expiry. Not unbanPlayerID, which drops every ip ban the player has and does not
            // put them back when the id is banned again. Withdrawing the scheduler's lifting token is
            // clearBanExpire's own business and is documented there.
            TempBan.clearBanExpire(uuid)

            // That call logs a database failure and carries on, so the row is read back rather than
            // telling a moderator the ban is permanent when the write never landed.
            val cleared = getPlayerData(uuid)?.banExpireDate == null && !pluginData.data.tempBans.containsKey(uuid)

            when {
                !cleared -> Log.warn(bundle["command.permaban.failed", uuid])
                previous == null -> Log.info(bundle["command.permaban.none", uuid])
                else -> {
                    Log.info(bundle["command.permaban.done", uuid])
                    Undo.record(null, "permaban", uuid, Undo.label(uuid)) {
                        scope.launch { TempBan.setBanExpire(it, previous) }
                    }
                }
            }

            // The ban list is game state, and this server may not be the one holding the ban: the expiry
            // is shared through the database, the ban is not.
            Core.app.post {
                if (cleared && !Vars.netServer.admins.isIDBanned(uuid)) {
                    Log.warn(bundle["command.permaban.not.banned", uuid])
                }
            }
        }
    }

    @ServerCommand("unban", "<player>", "Unban player")
    fun unban(arg: Array<out String>) {
        val bundle = Bundle()
        Log.info(bundle["command.unban.queued", arg[0]])
        scope.launch {
            val found = PlayerLookup.findExact(arg[0])
            if (PlayerLookup.ambiguous(found, arg[0], null)) return@launch
            val uuid = if (found is PlayerLookup.Result.Found) found.value.uuid else arg[0]
            TempBan.clearBanExpire(uuid)

            // unbanPlayerID/unbanPlayerIP mutate Vars.netServer.admins, engine state the main thread
            // also reads and writes; this coroutine is not that thread.
            Core.app.post {
                if (!Vars.netServer.admins.unbanPlayerID(uuid)) {
                    if (!Vars.netServer.admins.unbanPlayerIP(arg[0])) {
                        Log.warn(bundle[PlayerLookup.NOT_FOUND])
                    } else {
                        Log.info(bundle["command.unban.ip", arg[0]])
                    }
                } else {
                    Log.info(bundle["command.unban.id", uuid])
                    Undo.record(
                        null, "unban", uuid, Undo.label(uuid), "command.undo.button.banAgain"
                    ) { Undo.ban(it) }
                }
            }
        }
    }

    @ClientCommand("unban", "<player>", "Unban player")
    fun unban(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            val found = PlayerLookup.findExact(arg[0])
            if (PlayerLookup.ambiguous(found, arg[0], playerData)) return@launch
            val uuid = if (found is PlayerLookup.Result.Found) found.value.uuid else arg[0]
            TempBan.clearBanExpire(uuid)

            // unbanPlayerID/unbanPlayerIP mutate Vars.netServer.admins, engine state the main thread
            // also reads and writes; this coroutine is not that thread.
            Core.app.post {
                if (!Vars.netServer.admins.unbanPlayerID(uuid)) {
                    if (!Vars.netServer.admins.unbanPlayerIP(arg[0])) {
                        playerData.err(PLAYER_NOT_FOUND)
                    } else {
                        playerData.send("command.unban.ip", arg[0])
                    }
                } else {
                    playerData.send("command.unban.id", uuid)
                    Undo.record(
                        playerData, "unban", uuid, Undo.label(uuid), "command.undo.button.banAgain"
                    ) { Undo.ban(it) }
                }
            }
        }
    }

    @ClientCommand("undo", "[id/list]", "Undo the last administrative action.")
    fun undo(playerData: PlayerData, arg: Array<out String>) {
        val bundle = playerData.bundle
        val stack = Undo.stack(playerData.uuid)

        if (arg.isNotEmpty() && arg[0].equals("list", true)) {
            if (stack.isEmpty()) {
                playerData.send("command.undo.empty")
            } else {
                stack.forEach { entry ->
                    playerData.sendDirect(bundle["command.undo.list", entry.id.toString(), entry.description])
                }
            }
            return
        }

        val entry = Undo.take(playerData.uuid, if (arg.isEmpty()) null else (arg[0].toIntOrNull() ?: -1))
        if (entry == null) {
            if (stack.isEmpty()) playerData.send("command.undo.empty") else playerData.err("command.undo.invalid")
            return
        }
        entry.revert(entry.targetUuid)
        // Undo.take removed the entry before revert ran, so a refused revert cannot be retried and
        // saying "done" would leave the admin believing an action was undone that was not. Only the
        // setperm revert can fail this way, and only through the permission file: Permission.kt's
        // userFileProblem is persistent state, so checking it for every action would report a
        // permission error after a mute undo that worked. Same key and same shape as the forward
        // /setperm path above, so both halves say the same thing in the same words about the same file.
        val undoProblem = if (entry.action == "setperm") Permission.userFileProblem() else null
        if (undoProblem != null) {
            playerData.err("permission.user.file.invalid", undoProblem)
        } else {
            playerData.send("command.undo.done", entry.description)
        }
    }

    @ServerCommand("undo", "[id/list]", "Undo the last administrative action.")
    fun undo(arg: Array<out String>) {
        val bundle = Bundle()
        val stack = Undo.stack(Undo.CONSOLE)

        if (arg.isNotEmpty() && arg[0].equals("list", true)) {
            if (stack.isEmpty()) {
                Log.info(bundle["command.undo.empty"])
            } else {
                stack.forEach { entry ->
                    Log.info(bundle["command.undo.list", entry.id.toString(), entry.description])
                }
            }
            return
        }

        val entry = Undo.take(Undo.CONSOLE, if (arg.isEmpty()) null else (arg[0].toIntOrNull() ?: -1))
        if (entry == null) {
            Log.info(bundle[if (stack.isEmpty()) "command.undo.empty" else "command.undo.invalid"])
            return
        }
        entry.revert(entry.targetUuid)
        // Same reasoning as the client /undo above.
        val undoProblem = if (entry.action == "setperm") Permission.userFileProblem() else null
        if (undoProblem != null) {
            Log.warn(bundle["permission.user.file.invalid", undoProblem])
        } else {
            Log.info(bundle["command.undo.done", entry.description])
        }
    }

    @ClientCommand("unmute", "<player>", "Unmute player")
    fun unmute(playerData: PlayerData, arg: Array<out String>) {
        scope.launch {
            val target = PlayerLookup.offline(arg[0], playerData) ?: return@launch
            target.chatMuted = false
            target.update()
            playerData.send("command.unmute", target.name)
            Undo.record(playerData, "unmute", target.uuid, Undo.label(target.uuid)) { Undo.mute(it, true) }
        }
    }

    @ServerCommand("unmute", "<player>", "Unmute player")
    fun unmute(arg: Array<out String>) {
        val bundle = Bundle()
        scope.launch {
            val target = PlayerLookup.offline(arg[0]) ?: return@launch
            target.chatMuted = false
            target.update()
            Log.info(bundle["command.unmute", target.name])
            Undo.record(null, "unmute", target.uuid, Undo.label(target.uuid)) { Undo.mute(it, true) }
        }
    }

    @ClientCommand("url", "<command>", "Opens a URL contained in a specific command.")
    fun url(playerData: PlayerData, arg: Array<out String>) {
        // todo url 목록을 읽고 추가하는 기능 만들기
        when (arg[0]) {
            "effect" -> {
                Call.openURI(
                    playerData.player.con(),
                    "https://github.com/Anuken/Mindustry/blob/master/core/src/mindustry/content/Fx.java"
                )
            }

            else -> {}
        }
    }

    @ClientCommand("weather", "<weather> <seconds>", "Adds a weather effect to the map.")
    fun weather(playerData: PlayerData, arg: Array<out String>) {
        val weather = when (arg[0]) {
            "snow" -> Weathers.snow
            "sandstorm" -> Weathers.sandstorm
            "sporestorm" -> Weathers.sporestorm
            "fog" -> Weathers.fog
            "suspendParticles" -> Weathers.suspendParticles
            else -> Weathers.rain
        }
        try {
            val duration = arg[1].toInt()
            Call.createWeather(
                weather,
                (Random.nextDouble() * 100).toFloat(),
                (duration * 8).toFloat(),
                10f,
                10f
            )
        } catch (_: NumberFormatException) {
            playerData.err("command.weather.not.number")
        }
    }

    @ClientCommand("vote", "<kick/map/gg/skip/back/random/draw> [player/amount/world] [reason]", "Start voting")
    fun vote(playerData: PlayerData, arg: Array<out String>) {
        val coolTime = "command.vote.coolTime"
        val noReason = "command.vote.no.reason"
        val mapNotFound = "command.vote.map.not.exists"

        fun start(voteData: VoteData) {
            if (!isVoting) {
                isVoting = true
                if (!ModuleRuntime.startVote(voteData)) {
                    isVoting = false
                    playerData.err("command.vote.unavailable")
                }
            } else {
                playerData.err("command.vote.process")
            }
        }

        if (arg.isEmpty()) {
            playerData.err("command.vote.arg.empty")
            return
        }

        if (arg[0] == "reset") {
            if (!Permission.check(playerData, "vote.reset")) return
            isVoting = false
            nextVoteAvailable = timeSource.markNow()
            voterCooldown.clear()
            playerData.send("command.vote.reset")
            return
        }

        val cooldown = voterCooldown[playerData.uuid]
        if (cooldown != null) {
            if (!cooldown.hasPassedNow()) {
                playerData.err(coolTime)
                return
            } else {
                voterCooldown.remove(playerData.uuid)
            }
        }

        val solo = players.size == 1 && arg[0] == "map"
        // Mirrors VoteSystem.check()'s own electorate: team-scoped non-afk on a PvP map, server-wide
        // otherwise. The gate and the pass threshold have to count the same electorate, or a team that
        // is mostly eliminated (marked afk once unable to respawn - Team.derelict, a core wipe) can be
        // blocked from starting any vote by players elsewhere on the map who were never going to vote
        // in it anyway.
        val eligibleVoters = if (Vars.state.rules.pvp) {
            players.count { it.player.team() == playerData.player.team() && !it.afk }
        } else {
            players.count { !it.afk }
        }
        if (!solo && eligibleVoters <= 3 && !Permission.check(playerData, "vote.admin")) {
            playerData.err("command.vote.enough")
            return
        }

        when (arg[0]) {
            "kick" -> {
                if (!Permission.check(playerData, "vote.kick")) return
                if (arg.size != 3) {
                    playerData.err(noReason)
                    return
                }
                val target = PlayerLookup.online(arg[1], playerData)
                if (target != null) {
                    val targetData = players.find { it.uuid == target.uuid() }
                    if (Vars.state.rules.pvp && target.team() != playerData.player.team()) {
                        // The poll below is scoped to the starter's team, so a target on another team
                        // would be kicked by a vote that team never saw - and a player alone on a team
                        // would decide it unopposed. Vanilla refuses a cross-team votekick outright.
                        playerData.err("command.vote.kick.target.admin", target.plainName())
                    } else if (targetData != null && Permission.check(targetData, "kick.admin")) {
                        // A dedicated key: the reused admin key takes {0} and this call never supplied
                        // one, so the refusal rendered a literal "{0}" placeholder.
                        playerData.err("command.vote.kick.target.kickAdmin")
                    } else if (!nextVoteAvailable.hasPassedNow()) {
                        // gg/skip/random/draw all obey this cooldown; kick did not, exempting it from the
                        // same anti-spam limit every other vote type is held to.
                        playerData.err(coolTime)
                    } else {
                        val voteData = VoteData(
                            target = target,
                            targetUUID = target.uuid(),
                            reason = arg[2],
                            type = VoteType.Kick,
                            starter = playerData
                        )
                        if (Vars.state.rules.pvp) {
                            voteData.team = playerData.player.team()
                        }
                        nextVoteAvailable = timeSource.markNow().plus(2.minutes)
                        start(voteData)
                    }
                }
            }

            // vote map <map name> <reason>
            "map" -> {
                if (!Permission.check(playerData, "vote.map")) return
                if (arg.size == 1) {
                    playerData.err("command.vote.no.map")
                    return
                }
                if (arg.size == 2) {
                    playerData.err(noReason)
                    return
                }
                try {
                    var target: Map? = null
                    // Index lookup only applies when arg[1] is numeric; the name search below must run
                    // regardless, or a real map name never reaches it and this branch always fails.
                    if (arg[1].toIntOrNull() != null) {
                        val list = Vars.maps.all().sortedBy { a -> a.name() }
                        val arr = HashMap<Map, Int>()
                        list.forEachIndexed { index, map ->
                            arr[map] = index
                        }
                        arr.forEach {
                            if (it.value == arg[1].toInt()) {
                                target = it.key
                                return@forEach
                            }
                        }
                    }

                    if (target == null) {
                        target = Vars.maps.all().find { e -> e.plainName().contains(arg[1]) }
                    }

                    if (target != null) {
                        if (players.size != 1) {
                            // gg/skip/random/draw all obey this cooldown; map did not. The solo path below
                            // is a direct change with no vote and is rightly exempt, same as the gate above.
                            if (!nextVoteAvailable.hasPassedNow()) {
                                playerData.err(coolTime)
                            } else {
                                val voteData = VoteData(
                                    type = VoteType.Map,
                                    map = target,
                                    reason = arg[2],
                                    starter = playerData
                                )
                                nextVoteAvailable = timeSource.markNow().plus(2.minutes)
                                start(voteData)
                            }
                        } else {
                            isSurrender = true
                            val currentRule = Vars.state.rules.mode()
                            val reloader = WorldReloader()
                            reloader.begin()
                            Vars.world.loadMap(target, target.applyRules(currentRule))
                            Vars.state.rules = Vars.state.map.applyRules(currentRule)
                            Vars.logic.play()
                            reloader.end()
                            discardWorldHistory()
                        }
                    } else {
                        playerData.err(mapNotFound)
                    }
                } catch (_: IndexOutOfBoundsException) {
                    playerData.err(mapNotFound)
                }
            }

            // vote gg
            "gg" -> {
                if (!Permission.check(playerData, "vote.gg")) return
                if (nextVoteAvailable.hasPassedNow()) {
                    val voteData = VoteData(
                        type = VoteType.GameOver,
                        starter = playerData,
                    )
                    if (Vars.state.rules.pvp) {
                        voteData.team = playerData.player.team()
                    }
                    nextVoteAvailable = timeSource.markNow().plus(2.minutes)
                    start(voteData)
                } else {
                    playerData.err(coolTime)
                }
            }

            // vote skip <count>
            "skip" -> {
                if (!Permission.check(playerData, "vote.skip")) return
                if (arg.size == 1) {
                    playerData.send("command.vote.skip.wrong")
                } else if (arg[1].toIntOrNull() != null) {
                    val count = arg[1].toInt()
                    if (count <= 0) {
                        playerData.send("command.vote.skip.tooLow")
                    } else if (count > conf.command.skip.limit) {
                        playerData.send("command.vote.skip.tooMany")
                    } else {
                        if (nextVoteAvailable.hasPassedNow()) {
                            val voteData = VoteData(
                                type = VoteType.Skip,
                                wave = count,
                                starter = playerData
                            )
                            nextVoteAvailable = timeSource.markNow().plus(2.minutes)
                            start(voteData)
                        } else {
                            playerData.send(coolTime)
                        }
                    }
                }
            }

            // vote back <reason>
            "back" -> {
                if (!Permission.check(playerData, "vote.back")) return
                val rollbackFiles = Vars.saveDirectory.findAll { f -> f.name().startsWith("rollback_") && f.name().endsWith(".msav") }
                if (rollbackFiles.isEmpty) {
                    playerData.err("command.vote.back.no.file")
                    return
                }
                if (arg.size == 1) {
                    playerData.send(noReason)
                    return
                }
                // gg/skip/random/draw all obey this cooldown; back did not.
                if (!nextVoteAvailable.hasPassedNow()) {
                    playerData.err(coolTime)
                    return
                }
                val voteData = VoteData(
                    type = VoteType.Back,
                    reason = arg[1],
                    starter = playerData
                )
                nextVoteAvailable = timeSource.markNow().plus(2.minutes)
                start(voteData)
            }

            // vote random
            "random" -> {
                if (!Permission.check(playerData, "vote.random")) return
                if (nextVoteAvailable.hasPassedNow() || Permission.check(playerData, "vote.random.bypass")) {
                    val voteData = VoteData(
                        type = VoteType.Random,
                        starter = playerData
                    )
                    nextVoteAvailable = timeSource.markNow().plus(6.minutes)
                    start(voteData)
                } else {
                    playerData.err(coolTime)
                }
            }

            // vote draw
            "draw" -> {
                if (!Permission.check(playerData, "vote.draw")) return
                if (nextVoteAvailable.hasPassedNow()) {
                    val voteData = VoteData(
                        type = VoteType.Draw,
                        starter = playerData
                    )
                    nextVoteAvailable = timeSource.markNow().plus(2.minutes)
                    start(voteData)
                } else {
                    playerData.err(coolTime)
                }
            }

            else -> {
                playerData.send("command.help.vote")
            }
        }
    }

    @ClientCommand("votekick", "<player>", "Start kick voting")
    fun votekick(playerData: PlayerData, arg: Array<out String>) {
        val target = PlayerLookup.onlineData(arg[0], playerData) ?: return
        if (Permission.check(target, "kick.admin")) {
            playerData.err("command.vote.kick.target.admin")
        } else {
            vote(playerData, arrayOf("kick", "#${target.entityId}", "Kick"))
        }
    }

    @ClientCommand("nextmap", "[map]", "Set the next map to move to after game over")
    fun nextMap(playerData: PlayerData, arg: Array<out String>) {
        if (!Permission.check(playerData, "nextmap")) return

        if (arg.isEmpty()) {
            if (mapVotes.isEmpty()) {
                playerData.send("command.nextmap.vote.none")
            } else {
                val voteCount = HashMap<Map, Int>()
                mapVotes.values.forEach { map ->
                    voteCount[map] = voteCount.getOrDefault(map, 0) + 1
                }

                val sortedVotes = voteCount.entries.sortedByDescending { it.value }

                val message = StringBuilder(playerData.bundle["command.nextmap.vote.current"] + "\n")
                sortedVotes.forEach { (map, count) ->
                    message.append(playerData.bundle["command.nextmap.vote.count", map.plainName(), count] + "\n")
                }

                val playerVote = mapVotes[playerData.uuid]
                if (playerVote != null) {
                    message.append("\n" + playerData.bundle["command.nextmap.vote.your", playerVote.plainName()])
                } else {
                    message.append("\n" + playerData.bundle["command.nextmap.vote.none.yours"])
                }

                playerData.player.sendMessage(message.toString())
            }
            return
        }

        try {
            var target: Map? = null
            if (arg[0].toIntOrNull() != null) {
                val list = Vars.maps.all().sortedBy { a -> a.name() }
                val arr = HashMap<Map, Int>()
                list.forEachIndexed { index, map ->
                    arr[map] = index
                }
                arr.forEach {
                    if (it.value == arg[0].toInt()) {
                        target = it.key
                        return@forEach
                    }
                }
            }

            if (target == null) {
                target = Vars.maps.all().find { e -> e.plainName().contains(arg[0]) }
            }

            if (target != null) {
                val playerUuid = playerData.uuid
                // mapVotes only empties between rounds (the game-over handler clears it), so seeing it
                // empty right before this vote lands means a fresh round is starting and any earlier
                // admin override no longer applies to it.
                if (mapVotes.isEmpty()) {
                    nextMapAdminOverride = null
                }

                // Check if player already voted for this map
                if (mapVotes[playerUuid] == target) {
                    // Cancel the vote
                    mapVotes.remove(playerUuid)
                    playerData.send("command.nextmap.vote.canceled", target.plainName())
                    if (nextMapAdminOverride == target) {
                        nextMapAdminOverride = null
                    }
                } else {
                    // Record the vote
                    val previousVote = mapVotes.put(playerUuid, target)

                    if (previousVote != null) {
                        playerData.send("command.nextmap.vote.changed", previousVote.plainName(), target.plainName())
                    } else {
                        playerData.send("command.nextmap.vote.cast", target.plainName())
                    }

                    // If admin, they can still override the next map. That choice is remembered so the
                    // tally below - which runs after every vote from here on, including someone else's -
                    // re-affirms it instead of silently recomputing over it.
                    if (Permission.check(playerData, "nextmap.admin")) {
                        nextMapAdminOverride = target
                        Vars.maps.setNextMapOverride(target)
                        playerData.send("command.nextmap.set", target.plainName())
                        return
                    }
                }

                if (mapVotes.isNotEmpty()) {
                    val winner = nextMapAdminOverride ?: run {
                        val voteCount = HashMap<Map, Int>()
                        mapVotes.values.forEach { map ->
                            voteCount[map] = voteCount.getOrDefault(map, 0) + 1
                        }
                        voteCount.maxByOrNull { it.value }?.key
                    }
                    if (winner != null) {
                        Vars.maps.setNextMapOverride(winner)
                    }
                }
            } else {
                playerData.err("command.nextmap.not.found")
            }
        } catch (_: IndexOutOfBoundsException) {
            playerData.err("command.nextmap.not.found")
        }
    }

    @ClientCommand("fuck", "[command]", "Corrects and executes a command with typos")
    fun fuck(playerData: PlayerData, arg: Array<out String>) {
        if (arg.isEmpty()) {
            playerData.err("command.fuck.no.command")
            return
        }

        val inputCommand = arg.joinToString(" ")

        val commandParts = inputCommand.split(" ", limit = 2)
        val commandName = commandParts[0]

        val availableCommands = Vars.netServer.clientCommands.commandList.map { it.text }

        val closestCommand = availableCommands.minByOrNull { levenshteinDistance(commandName, it) }
        if (closestCommand != null) {
            val args = if (commandParts.size > 1) " ${commandParts[1]}" else ""
            val fullCommand = "/$closestCommand$args"
            Vars.netServer.clientCommands.handleMessage(fullCommand, playerData.player)
        }
    }

    @ClientCommand("ws", "[args...]", "WorldEdit selection and block manipulation")
    fun ws(playerData: PlayerData, arg: Array<out String>) {
        val uuid = playerData.uuid

        // Parse arguments - always split by whitespace for all args
        val allArgs = arg.joinToString(" ").trim()
        val parsedArgs = if (allArgs.isEmpty()) {
            emptyList()
        } else {
            allArgs.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        }

        // If no args, toggle selection mode
        if (parsedArgs.isEmpty()) {
            val selection = worldEditSelection[uuid] ?: WorldEditSelection()
            if (selection.selecting) {
                // Cancel selection
                selection.selecting = false
                selection.selectionComplete = false
                selection.startX = -1
                selection.startY = -1
                selection.endX = -1
                selection.endY = -1
                worldEditSelection[uuid] = selection
                playerData.send("command.ws.cancel")
            } else {
                // Start selection mode
                selection.selecting = true
                selection.selectionComplete = false
                selection.startX = -1
                selection.startY = -1
                selection.endX = -1
                selection.endY = -1
                worldEditSelection[uuid] = selection
                playerData.send("command.ws.selecting")
            }
            return
        }

        // Get selection for operations
        val selection = worldEditSelection[uuid]

        when (parsedArgs[0]) {
            "f" -> {
                // Fill: /ws f <block>
                if (parsedArgs.size < 2) {
                    playerData.err("command.ws.invalid.fill")
                    return
                }
                if (selection == null || !selection.selectionComplete) {
                    playerData.err("command.ws.no.selection")
                    return
                }
                if (getRegionSize(selection) > conf.command.worldEdit.maxRegionSize) {
                    playerData.err("command.ws.region.too.large", getRegionSize(selection), conf.command.worldEdit.maxRegionSize)
                    return
                }
                val blockName = parsedArgs[1]
                val block = findBlockByName(blockName)
                if (block == null) {
                    playerData.err("command.ws.block.not.found", blockName)
                    return
                }
                fillRegion(playerData, selection, block)
                playerData.send("command.ws.fill.success", blockName, getRegionSize(selection))
            }
            "r" -> {
                // Replace: /ws r <fromBlock> <toBlock>
                if (parsedArgs.size < 3) {
                    playerData.err("command.ws.invalid.replace")
                    return
                }
                if (selection == null || !selection.selectionComplete) {
                    playerData.err("command.ws.no.selection")
                    return
                }
                if (getRegionSize(selection) > conf.command.worldEdit.maxRegionSize) {
                    playerData.err("command.ws.region.too.large", getRegionSize(selection), conf.command.worldEdit.maxRegionSize)
                    return
                }
                val fromName = parsedArgs[1]
                val toName = parsedArgs[2]
                val fromBlock = findBlockByName(fromName)
                val toBlock = findBlockByName(toName)
                if (fromBlock == null) {
                    playerData.err("command.ws.block.not.found", fromName)
                    return
                }
                if (toBlock == null) {
                    playerData.err("command.ws.block.not.found", toName)
                    return
                }
                replaceRegion(playerData, selection, fromBlock, toBlock)
                playerData.send("command.ws.replace.success", fromName, toName)
            }
            "d" -> {
                // Delete: /ws d
                if (selection == null || !selection.selectionComplete) {
                    playerData.err("command.ws.no.selection")
                    return
                }
                if (getRegionSize(selection) > conf.command.worldEdit.maxRegionSize) {
                    playerData.err("command.ws.region.too.large", getRegionSize(selection), conf.command.worldEdit.maxRegionSize)
                    return
                }
                deleteRegion(playerData, selection)
                playerData.send("command.ws.delete.success", getRegionSize(selection))
            }
            else -> {
                playerData.err("command.ws.invalid")
            }
        }

        // Reset selected zone
        worldEditSelection.remove(uuid)
    }

    private fun getRegionSize(selection: WorldEditSelection): Int {
        val minX = minOf(selection.startX, selection.endX)
        val maxX = maxOf(selection.startX, selection.endX)
        val minY = minOf(selection.startY, selection.endY)
        val maxY = maxOf(selection.startY, selection.endY)
        return (maxX - minX + 1) * (maxY - minY + 1)
    }

    private fun findBlockByName(name: String): mindustry.world.Block? {
        // Try content blocks by name first (e.g., "copper-wall")
        Vars.content.blocks().find { it.name == name }?.let { return it }
        // Try Blocks class field directly (e.g., "copperWall")
        val blockClass = Blocks::class.java
        for (f in blockClass.fields) {
            if (f.name.equals(name, ignoreCase = true)) {
                try { return f.get(null) as? mindustry.world.Block } catch (_: Exception) {}
            }
        }
        // Try hyphenated form (e.g., "copper-wall" -> "copperWall")
        val parts = name.split('-', '_')
        val camelCase = parts.joinToString("") { if (it == parts[0]) it.lowercase() else it.replaceFirstChar { c -> c.uppercaseChar() } }
        for (f in blockClass.fields) {
            if (f.name.equals(camelCase, ignoreCase = true)) {
                try { return f.get(null) as? mindustry.world.Block } catch (_: Exception) {}
            }
        }
        // Fallback: case-insensitive content blocks
        return Vars.content.blocks().find { it.name.equals(name, ignoreCase = true) }
    }

    private fun fillRegion(playerData: PlayerData, selection: WorldEditSelection, block: mindustry.world.Block) {
        val minX = minOf(selection.startX, selection.endX)
        val maxX = maxOf(selection.startX, selection.endX)
        val minY = minOf(selection.startY, selection.endY)
        val maxY = maxOf(selection.startY, selection.endY)

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                Call.setTile(Vars.world.tile(x, y), block, playerData.player.team(), 0)
            }
        }
    }

    private fun replaceRegion(playerData: PlayerData, selection: WorldEditSelection, fromBlock: mindustry.world.Block, toBlock: mindustry.world.Block) {
        val minX = minOf(selection.startX, selection.endX)
        val maxX = maxOf(selection.startX, selection.endX)
        val minY = minOf(selection.startY, selection.endY)
        val maxY = maxOf(selection.startY, selection.endY)

        val fromId = fromBlock.id
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val tile = Vars.world.tile(x, y)
                if (tile.block().id == fromId) {
                    Call.setTile(tile, toBlock, playerData.player.team(), 0)
                }
            }
        }
    }

    private fun deleteRegion(playerData: PlayerData, selection: WorldEditSelection) {
        val minX = minOf(selection.startX, selection.endX)
        val maxX = maxOf(selection.startX, selection.endX)
        val minY = minOf(selection.startY, selection.endY)
        val maxY = maxOf(selection.startY, selection.endY)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                Call.setTile(Vars.world.tile(x, y), Blocks.air, playerData.player.team(), 0)
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    @ServerCommand("gen", description = "Generate wiki docs")
    fun genDocs() {
        if (System.getenv("DEBUG_KEY") != null) {
            class StringUtils {
                // Source from https://howtodoinjava.com/java/string/escape-html-encode-string/
                private val htmlEncodeChars = HashMap<Char, String>()
                fun encodeHtml(source: String?): String? {
                    return encode(source)
                }

                private fun encode(source: String?): String? {
                    if (null == source) return null
                    var encode: StringBuilder? = null
                    val encodeArray = source.toCharArray()
                    var match = -1
                    var difference: Int
                    for (i in encodeArray.indices) {
                        val charEncode = encodeArray[i]
                        if (htmlEncodeChars.containsKey(charEncode)) {
                            if (null == encode) encode = StringBuilder(source.length)
                            difference = i - (match + 1)
                            if (difference > 0) encode.appendRange(encodeArray, match + 1, match + 1 + difference)
                            encode.append(htmlEncodeChars[charEncode])
                            match = i
                        }
                    }
                    return if (null == encode) {
                        source
                    } else {
                        difference = encodeArray.size - (match + 1)
                        if (difference > 0) encode.appendRange(encodeArray, match + 1, match + 1 + difference)
                        encode.toString()
                    }
                }

                init {
                    htmlEncodeChars['\u0026'] = "&amp;"
                    htmlEncodeChars['\u003C'] = "&lt;"
                    htmlEncodeChars['\u003E'] = "&gt;"
                    htmlEncodeChars['\u0022'] = "&quot;"
                    htmlEncodeChars['\u00A0'] = "&nbsp;"
                }
            }


            val server = "## Server commands\n| Command | Parameter | Description |\n|:---|:---|:--- |\n"
            val client = "## Client commands\n| Command | Parameter | Description |\n|:---|:---|:--- |\n"
            val time = "README.md Generated time: ${
                Clock.System.now().toLocalDateTime(systemTimezone)
                    .format(LocalDateTime.Formats.ISO)
            }"

            val result = StringBuilder()

            for (functions in this::class.declaredFunctions) {
                val annotation = functions.findAnnotation<ClientCommand>()
                if (annotation != null) {
                    val temp =
                        "| ${annotation.name} | ${StringUtils().encodeHtml(annotation.parameter)} | ${annotation.description} |\n"
                    result.append(temp)
                }
            }

            val tmp = "$client$result\n\n"

            result.clear()
            for (functions in this::class.declaredFunctions) {
                val annotation = functions.findAnnotation<ServerCommand>()
                if (annotation != null) {
                    val temp =
                        "| ${annotation.name} | ${StringUtils().encodeHtml(annotation.parameter)} | ${annotation.description} |\n"
                    result.append(temp)
                }
            }

            println("$tmp$server$result\n\n\n$time")
        }
    }

    @ServerCommand("reload", description = "Reload essential plugin configs.")
    fun reload() {
        scope.launch {
            try {
                Permission.load()
            } catch (e: Exception) {
                Log.err("Failed to reload the permission configuration.", e)
                return@launch
            }
            Core.app.post {
                try {
                    Log.info(Bundle()["config.permission.updated"])
                    Main.conf = Main.reloadConf()
                    ModuleRuntime.reloadEnabledConfigurations()
                    // feature.vote.enabled, enableVotekick and bannedCommands are read once at boot, so
                    // reloading conf alone leaves the handler holding what it was given then. Idempotent:
                    // CommandHandler.register replaces by name and removeCommand is a no-op on an absent
                    // name, so this lands on the same end state whatever the handler started from.
                    Main.syncClientCommands(Vars.netServer.clientCommands)
                    Log.info(Bundle()["config.reloaded"])
                } catch (e: Exception) {
                    Log.err("Failed to reload the plugin configuration, keeping the previous one.", e)
                }
            }
        }
    }

    @ServerCommand("reloadplayer", "<uuid/name>", "Reload the player data of an online player.")
    fun reloadPlayer(arg: Array<out String>) {
        val target = PlayerLookup.online(arg[0]) ?: return

        scope.launch {
            cancelPlayerDataRetry(target.uuid())
            val result = loadJoinedPlayerData(target, target.name())
            if (result.duplicateName) {
                Log.err("Player data for ${target.plainName()} (${target.uuid()}) has a duplicate name.")
                return@launch
            }
            val data = result.data
            if (data == null) {
                Log.err("Player data for ${target.plainName()} (${target.uuid()}) could not be loaded.")
                return@launch
            }

            val current = findPlayerData(target.uuid())
            if (current != null && current.temporary) {
                swapTemporaryPlayerData(data, current)
            } else {
                data.player = target
                firePlayerDataLoad(data)
            }
            Log.info("Player data for ${target.plainName()} (${target.uuid()}) has been reloaded.")
        }
    }

    @ServerCommand("debug", "[parameter...]", "Debug any commands")
    fun debug(arg: Array<out String>) {
        if (arg.isNotEmpty()) {
            if (arg[0] == "discord") {
                scope.launch {
                    for (a in players) {
                        a.discordID = "1"
                        a.update()
                    }
                }
            }
        }
        println(pluginData.toString())
        for (a in players) {
            println(a.toString())
        }
    }

    @ServerCommand("mergeplayer", "<from_uuid> <to_uuid>", "Merge two player accounts (from -> to).")
    fun mergePlayer(arg: Array<out String>) {
        if (arg.size < 2) {
            Log.warn("Usage: mergeplayer <from_uuid> <to_uuid>")
            return
        }
        val from = arg[0]
        val to = arg[1]
        scope.launch {
            try {
                val result = mergePlayerAccounts(from, to)
                Log.info(result)
            } catch (e: Exception) {
                Log.err("Merge failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    @ServerCommand("delete", "<uuid/name/id>", "Delete player data and achievements from database")
    fun delete(arg: Array<out String>) {
        val bundle = Bundle()
        if (arg.isEmpty()) {
            Log.warn(bundle["command.delete.usage"])
            return
        }
        val target = arg[0]
        val idVal = target.toUIntOrNull()

        scope.launch {
            try {
                val matches = suspendTransaction {
                    PlayerTable.selectAll().where {
                        val nameCond = PlayerTable.name.lowerCase() eq target.lowercase()
                        if (idVal != null) {
                            (PlayerTable.id eq idVal) or (PlayerTable.uuid eq target) or nameCond
                        } else {
                            (PlayerTable.uuid eq target) or nameCond
                        }
                    }.mapToPlayerDataList()
                }

                if (matches.isEmpty()) {
                    Log.warn(bundle["command.delete.not.found", target])
                    return@launch
                }

                if (matches.size > 1) {
                    Log.info(bundle["command.delete.multiple"])
                    matches.forEach { player ->
                        Log.info(bundle["command.delete.multiple.format", player.id, player.name, player.uuid, player.exp, player.level, player.discordID ?: "null"])
                    }
                    return@launch
                }

                val playerToDelete = matches.first()
                suspendTransaction {
                    AchievementTable.deleteWhere { AchievementTable.playerId eq playerToDelete.id }
                    PlayerTable.deleteWhere { PlayerTable.id eq playerToDelete.id }
                }
                Log.info(bundle["command.delete.success", playerToDelete.name, playerToDelete.id, playerToDelete.uuid])
            } catch (e: Exception) {
                Log.err(bundle["command.delete.failed", e.message ?: "Unknown error"])
                e.printStackTrace()
            }
        }
    }

    private fun selectTeam(arg: String): Team {
        return when {
            "derelict".first() == arg.first() -> Team.derelict
            "sharded".first() == arg.first() -> Team.sharded
            "crux".first() == arg.first() -> Team.crux
            "green".first() == arg.first() -> Team.green
            "malis".first() == arg.first() -> Team.malis
            "blue".first() == arg.first() -> Team.blue
            "derelict".contains(arg[0], true) -> Team.derelict
            "sharded".contains(arg[0], true) -> Team.sharded
            "crux".contains(arg[0], true) -> Team.crux
            "green".contains(arg[0], true) -> Team.green
            "malis".contains(arg[0], true) -> Team.malis
            "blue".contains(arg[0], true) -> Team.blue
            else -> Vars.state.rules.defaultTeam
        }
    }

    object Exp {
        private const val BASE_XP = 750
        private const val EXPONENT = 1.06
        private fun calcXpForLevel(level: Int): Double {
            return BASE_XP + BASE_XP * level.toDouble().pow(EXPONENT)
        }

        fun calculateFullTargetXp(level: Int): Double {
            var requiredXP = 0.0
            for (i in 0..level) requiredXP += calcXpForLevel(i)
            return requiredXP
        }

        fun calculateLevel(xp: Int): Int {
            var level = 0
            var maxXp = calcXpForLevel(0)
            do maxXp += calcXpForLevel(++level) while (maxXp < xp)
            return level
        }

        operator fun get(target: PlayerData): String {
            val currentlevel = target.level
            val max = calculateFullTargetXp(currentlevel).toInt()
            val xp = target.exp
            val levelXp = max - xp
            val level = calculateLevel(xp)
            target.level = level
            return "$xp (${floor(levelXp.toDouble()).toInt()}) / ${floor(max.toDouble()).toInt()}"
        }
    }

    data class WorldEditSelection(
        var startX: Int = -1,
        var startY: Int = -1,
        var endX: Int = -1,
        var endY: Int = -1,
        var selecting: Boolean = false,
        var selectionComplete: Boolean = false
    )
}
