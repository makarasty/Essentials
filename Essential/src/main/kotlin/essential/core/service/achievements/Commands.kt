package essential.core.service.achievements

import arc.util.Strings
import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.permission.Permission
import essential.common.util.PlayerLookup
import ksp.command.ClientCommand
import ksp.command.ServerCommand
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

class Commands {
    @ClientCommand(name = "ach", parameter = "[page]", description = "Show your achievements")
    fun achievements(playerData: PlayerData, args: Array<String>) {
        val temp: MutableList<String?> = arrayListOf()
        val bundle = try {
            Bundle.resolve("bundles/achievements/bundle", Locale.forLanguageTag(playerData.player.locale().replace("_", "-")))
        } catch (e: MissingResourceException) {
            Bundle.resolve("bundles/achievements/bundle", Locale.ENGLISH)
        }

        // Each achievement's success() is computed once and reused: it used to be called twice for every
        // hidden-and-passing achievement (once for visibility, once for the "cleared" marker), and
        // Achievement.mapHash re-reads and MD5-hashes the whole map file on every call - see Achievement.kt.
        for (ach in Achievement.entries) {
            val achieved = ach.success(playerData)
            if (!ach.isHidden || achieved) {
                val name: String = ach.toString().lowercase(Locale.getDefault())
                val cleared = if (achieved) "[sky][" + bundle.getString("cleared") + "][] " else ""
                temp.add(cleared + bundle.getString("achievement.$name") + "[orange] (" + ach.current(playerData) + " / " + ach.value() + ")[][]\n")
                temp.add("[yellow]" + bundle.getString("description.$name") + "[]\n")
                temp.add("\n")
            }
        }

        val result = StringBuilder()
        val per = 9
        var page = if (args.isEmpty()) 1 else abs(Strings.parseInt(args[0]))
        val pages = ceil((temp.size.toFloat() / per).toDouble()).toInt()
        page--

        if (page !in 0..<pages) {
            playerData.err("command.page.range", pages)
            return
        }

        result.append("[orange]-- ").append(bundle.getString("command.page"))
            .append("[lightgray] ").append(page + 1)
            .append("[gray]/[lightgray]").append(pages)
            .append("[orange] --[white]\n")

        for (a in per * page..<min(per * (page + 1), temp.size)) {
            result.append(temp[a])
        }

        val msg = result.substring(0, result.length - 1)
        playerData.player.sendMessage(msg)
    }

    @ServerCommand(name = "setmapprovider", parameter = "<player>", description = "Set the MapProvider achievement for a player")
    fun setMapProvider(args: Array<String>) {
        if (args.isEmpty()) {
            println("Please specify a player name")
            return
        }

        val player = PlayerLookup.onlineData(args[0]) ?: return

        // Set the achievement
        player.status["record.map.provider"] = "1"
        if (Achievement.MapProvider.success(player)) {
            Achievement.MapProvider.set(player)
            println("MapProvider achievement set for player: ${player.name}")
        } else {
            println("Failed to set MapProvider achievement for player: ${player.name}")
        }
    }

    @ServerCommand(name = "setfeedbackprovider", parameter = "<player>", description = "Set the FeedbackProvider achievement for a player")
    fun setFeedbackProvider(args: Array<String>) {
        if (args.isEmpty()) {
            println("Please specify a player name")
            return
        }

        val player = PlayerLookup.onlineData(args[0]) ?: return

        // Set the achievement
        player.status["record.feedback.provider"] = "1"
        if (Achievement.FeedbackProvider.success(player)) {
            Achievement.FeedbackProvider.set(player)
            println("FeedbackProvider achievement set for player: ${player.name}")
        } else {
            println("Failed to set FeedbackProvider achievement for player: ${player.name}")
        }
    }

    @ClientCommand(name = "setmapprovider", parameter = "<player>", description = "Set the MapProvider achievement for a player")
    fun clientSetMapProvider(playerData: PlayerData, args: Array<String>) {
        // Check if the player has admin permission
        if (!Permission.check(playerData, "admin")) {
            playerData.err("command.permission.false")
            return
        }

        if (args.isEmpty()) {
            playerData.err("command.player.name.required")
            return
        }

        val player = PlayerLookup.onlineData(args[0], playerData) ?: return

        // Set the achievement
        player.status["record.map.provider"] = "1"
        if (Achievement.MapProvider.success(player)) {
            Achievement.MapProvider.set(player)
            playerData.send("command.setmapprovider.success", player.name)
        } else {
            playerData.err("command.setmapprovider.failure", player.name)
        }
    }

    @ClientCommand(name = "setfeedbackprovider", parameter = "<player>", description = "Set the FeedbackProvider achievement for a player")
    fun clientSetFeedbackProvider(playerData: PlayerData, args: Array<String>) {
        // Check if the player has admin permission
        if (!Permission.check(playerData, "admin")) {
            playerData.err("command.permission.false")
            return
        }

        if (args.isEmpty()) {
            playerData.err("command.player.name.required")
            return
        }

        val player = PlayerLookup.onlineData(args[0], playerData) ?: return

        // Set the achievement
        player.status["record.feedback.provider"] = "1"
        if (Achievement.FeedbackProvider.success(player)) {
            Achievement.FeedbackProvider.set(player)
            playerData.send("command.setfeedbackprovider.success", player.name)
        } else {
            playerData.err("command.setfeedbackprovider.failure", player.name)
        }
    }
}
