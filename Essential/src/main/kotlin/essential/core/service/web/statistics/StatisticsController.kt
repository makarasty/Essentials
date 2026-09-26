package essential.core.service.web.statistics

import arc.Core
import arc.Events
import arc.util.Log
import essential.common.database.data.getAverageContribution
import essential.common.database.data.getContributionCount
import essential.common.database.data.getPlayerDataByAccountID
import essential.common.permission.Permission
import essential.common.playTime
import essential.common.players
import essential.common.systemTimezone
import essential.common.util.size
import essential.common.util.toHString
import essential.core.isGlobalMute
import essential.core.service.web.auth.UserSession
import essential.core.service.web.onGameThread
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import java.lang.reflect.Method
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class WebPlayerInfo(
    val name: String,
    val playTime: String
)

@Serializable
data class ServerStatus(
    val map: String,
    val players: List<WebPlayerInfo>,
    val tps: Float,
    val wave: Int,
    val gameTime: String,
    val mode: String,
    val activeTeams: Int
)

@Serializable
data class ChatMessage(
    val player: String,
    val message: String,
    val time: Long = System.currentTimeMillis(),
    val isWeb: Boolean = false
)

@Serializable
data class ContributionEntry(
    val name: String,
    val current: Double,
    val average: Double,
    val games: Int,
    val team: String? = null,
    val teamColor: String? = null
)

@Serializable
data class StatusDataPoint(
    val time: Long,
    val tps: Float,
    val players: Int,
    val units: Int,
    val buildings: Int,
    val resources: Map<String, Int>? = null,
    val teamResources: Map<String, Int>? = null,
    val teamUnits: Map<String, Int>? = null,
    val teamBuildings: Map<String, Int>? = null
)

class StatisticsController {
    val chatHistory = Collections.synchronizedList(mutableListOf<ChatMessage>())
    val statusHistory = Collections.synchronizedList(mutableListOf<StatusDataPoint>())

    fun init(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(60000.milliseconds)
                try {
                    // Groups and Vars.state belong to the game thread; this loop runs on a coroutine one.
                    onGameThread { recordStatusPoint() }
                } catch (e: Exception) {
                    Log.err("Error recording status point", e)
                }
            }
        }

        Events.on(EventType.PlayerChatEvent::class.java) { event ->
            val player = event.player
            val message = event.message

            // Add the chat message to the chat history
            val chatMessage = ChatMessage(player.name(), message, isWeb = false)
            synchronized(chatHistory) {
                chatHistory.add(chatMessage)
                if (chatHistory.size > 100) {
                    chatHistory.removeAt(0)
                }
            }

            Log.debug("Chat message added to history: ${player.name()}: $message")
        }
    }

    private fun getServerStatus(): ServerStatus {
        val playersList = mutableListOf<WebPlayerInfo>()
        Groups.player.each { player ->
            val playerData = players.find { it.uuid == player.uuid() }
            val playtimeStr = if (playerData != null) {
                val joinInstant = playerData.lastLoginDate.toInstant(systemTimezone)
                val elapsedSeconds = (Clock.System.now().toEpochMilliseconds() - joinInstant.toEpochMilliseconds()) / 1000
                elapsedSeconds.seconds.toHString()
            } else {
                "00:00"
            }
            playersList.add(WebPlayerInfo(player.name(), playtimeStr))
        }

        val mode = when {
            Vars.state == null || Vars.state.isMenu -> "none"
            Vars.state.rules.pvp -> "pvp"
            Vars.state.rules.mode() == mindustry.game.Gamemode.survival || Vars.state.rules.mode() == mindustry.game.Gamemode.attack -> "wave"
            else -> "none"
        }

        val activeTeams = if (Vars.state != null && !Vars.state.isMenu && Vars.state.teams != null && Vars.state.teams.active != null) {
            Vars.state.teams.active.size
        } else {
            0
        }

        return ServerStatus(
            map = if (Vars.state != null && Vars.state.map != null) Vars.state.map.name() else "Menu",
            players = playersList,
            tps = Core.graphics.framesPerSecond.toFloat(),
            wave = if (Vars.state != null) Vars.state.wave else 0,
            gameTime = playTime,
            mode = mode,
            activeTeams = activeTeams
        )
    }

    private fun recordStatusPoint() {
        if (Vars.state == null || Vars.state.isMenu) return

        val mode = when {
            Vars.state == null || Vars.state.isMenu -> "none"
            Vars.state.rules.pvp -> "pvp"
            Vars.state.rules.mode() == mindustry.game.Gamemode.survival || Vars.state.rules.mode() == mindustry.game.Gamemode.attack -> "wave"
            else -> "none"
        }

        var resources: Map<String, Int>? = null
        var teamResources: Map<String, Int>? = null
        var teamUnits: Map<String, Int>? = null
        var teamBuildings: Map<String, Int>? = null

        if (mode == "wave") {
            val resMap = mutableMapOf<String, Int>()
            val cores = Vars.state.teams.cores(Vars.state.rules.defaultTeam)
            if (cores != null && !cores.isEmpty) {
                Vars.content.items().forEach { item ->
                    if (!item.isHidden) {
                        var sum = 0
                        cores.forEach { core ->
                            sum += core.items.get(item)
                        }
                        resMap[item.name] = sum
                    }
                }
            }
            resources = resMap
        } else if (mode == "pvp") {
            val teamResMap = mutableMapOf<String, Int>()
            val teamUnitsMap = mutableMapOf<String, Int>()
            val teamBuildingsMap = mutableMapOf<String, Int>()

            if (Vars.state.teams != null && Vars.state.teams.active != null) {
                // One pass over each group, not one per team.
                val unitCounts = HashMap<Team, Int>()
                for (unit in Groups.unit) {
                    unitCounts[unit.team] = (unitCounts[unit.team] ?: 0) + 1
                }
                val buildingCounts = HashMap<Team, Int>()
                for (build in Groups.build) {
                    buildingCounts[build.team] = (buildingCounts[build.team] ?: 0) + 1
                }

                Vars.state.teams.active.forEach { teamData ->
                    val team = teamData.team
                    val teamName = team.name

                    // Compute team resources (sum of all items across all cores of this team)
                    val cores = teamData.cores
                    var totalRes = 0
                    if (cores != null && !cores.isEmpty) {
                        Vars.content.items().forEach { item ->
                            if (!item.isHidden) {
                                cores.forEach { core ->
                                    totalRes += core.items.get(item)
                                }
                            }
                        }
                    }
                    teamResMap[teamName] = totalRes

                    teamUnitsMap[teamName] = unitCounts[team] ?: 0
                    teamBuildingsMap[teamName] = buildingCounts[team] ?: 0
                }
            }
            teamResources = teamResMap
            teamUnits = teamUnitsMap
            teamBuildings = teamBuildingsMap
        }

        val point = StatusDataPoint(
            time = System.currentTimeMillis(),
            tps = Core.graphics.framesPerSecond.toFloat(),
            players = Groups.player.size(),
            units = Groups.unit.size,
            buildings = Groups.build.size,
            resources = resources,
            teamResources = teamResources,
            teamUnits = teamUnits,
            teamBuildings = teamBuildings
        )

        synchronized(statusHistory) {
            statusHistory.add(point)
            if (statusHistory.size > 1440) {
                statusHistory.removeAt(0)
            }
        }
    }

    /**
     * The chat service is an optional module: building with `-PexcludeModules=chat` drops its whole
     * package from the source set while this file stays, so the blacklist cannot be a direct call. Same
     * reason and same shape as WebServer's optional achievements route. No module means no list, so
     * nothing to refuse against.
     */
    private val blacklistCheck: Method? by lazy {
        try {
            Class.forName("essential.core.service.chat.EventKt")
                .getMethod("isChatBlacklisted", String::class.java)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    private fun isBlacklisted(message: String): Boolean = try {
        blacklistCheck?.invoke(null, message) as? Boolean == true
    } catch (e: ReflectiveOperationException) {
        Log.err("Failed to consult the chat blacklist", e)
        false
    }

    private fun sanitizeMessage(message: String): String {
        // Remove potentially dangerous characters and HTML tags. The ampersand goes first, or it
        // re-escapes the entities the replacements below it introduce.
        return message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            // Mindustry's own markup, not HTML: an unescaped '[' lets a web message paint itself in the
            // colours of a server line or of another player's name. '[[' is how the engine renders a
            // literal one.
            .replace("[", "[[")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
    }

    suspend fun handleGetContribution(call: ApplicationCall) {
        if (!essential.core.Main.conf.module.contribution) {
            return call.respond(HttpStatusCode.Forbidden, "Contribution module is disabled")
        }
        // Live: current online players, each with this game's contribution and their overall average.
        // In PvP, include team so the client can group players by team.
        // The rules and a player's team are read on the game thread; the database lookups are kept
        // out of that block, because they must not run inside a server frame.
        val snapshot = onGameThread {
            val isPvp = Vars.state != null && !Vars.state.isMenu && Vars.state.rules.pvp
            players.toList().map { data ->
                val team = if (isPvp) data.player.team() else null
                data to ContributionEntry(
                    name = data.name,
                    current = data.currentContribution,
                    average = 0.0,
                    games = 0,
                    team = team?.name,
                    teamColor = team?.color?.toString()?.let { "#$it" }
                )
            }
        }
        val entries = snapshot.map { (data, entry) ->
            entry.copy(average = getAverageContribution(data), games = getContributionCount(data))
        }.sortedByDescending { it.current }
        call.respond(entries)
    }

    suspend fun handleGetChat(call: ApplicationCall) {
        val messages = synchronized(chatHistory) { chatHistory.toList() }
            .filter { !it.message.startsWith("/") }
            .sortedBy { it.time }
        call.respond(messages)
    }

    suspend fun handlePostChat(call: ApplicationCall) {
        val session = call.sessions.get<UserSession>()
            ?: return call.respond(HttpStatusCode.Unauthorized)
        val message = call.receiveText()

        // Validate chat message
        if (message.isBlank() || message.length > 100) {
            return call.respond(HttpStatusCode.BadRequest, "Invalid message")
        }

        // Every filter registered through admins.filterMessage takes a connected Player, and this
        // endpoint authenticates a database row instead, so filterMessage cannot be reached from here.
        // The checks that do not need a Player are applied directly, against the same blacklist body the
        // registered filter uses. The vote filter is deliberately not among them: a web sender who is not
        // in the game is not a participant in a vote.
        val data = getPlayerDataByAccountID(session.accountID)
            ?: return call.respond(HttpStatusCode.Forbidden, "Chat is disabled")
        if (data.chatMuted) {
            return call.respond(HttpStatusCode.Forbidden, "You are muted")
        }
        if (isGlobalMute && !Permission.check(data, "chat.admin")) {
            return call.respond(HttpStatusCode.Forbidden, "Chat is disabled")
        }
        if (isBlacklisted(message)) {
            return call.respond(HttpStatusCode.Forbidden, "Message blocked")
        }

        // Sanitize message to prevent code injection
        val sanitizedMessage = sanitizeMessage(message)

        // Broadcast before recording: a send that failed must not show up in the web history as if
        // it had gone out.
        onGameThread { Call.sendMessage("[cyan]<WEB>[white] ${data.name}: $sanitizedMessage") }

        // Add to chat history
        val chatMessage = ChatMessage(data.name, sanitizedMessage, isWeb = true)
        synchronized(chatHistory) {
            chatHistory.add(chatMessage)
            if (chatHistory.size > 100) {
                chatHistory.removeAt(0)
            }
        }

        call.respond(HttpStatusCode.OK)
    }

    suspend fun handleGetHistory(call: ApplicationCall) {
        val history = synchronized(statusHistory) { statusHistory.toList() }
        call.respond(history)
    }

    suspend fun handleGetServerStatus(call: ApplicationCall) {
        val status = onGameThread { getServerStatus() }
        call.respond(status)
    }
}

fun Route.statisticsRoutes(controller: StatisticsController) {
    route("/api/server") {
        // Every route here reports on the players currently online, so all of them belong inside the
        // authenticate block: authenticate wraps only the routes declared in its own lambda.
        authenticate("auth-session") {
            get("/status") {
                controller.handleGetServerStatus(call)
            }

            get("/contribution") {
                controller.handleGetContribution(call)
            }

            get("/chat") {
                controller.handleGetChat(call)
            }

            post("/chat") {
                controller.handlePostChat(call)
            }

            get("/history") {
                controller.handleGetHistory(call)
            }
        }
    }
}
