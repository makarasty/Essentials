package essential.core.service.chat

import essential.common.database.data.PlayerData
import essential.common.rootPath
import essential.common.util.PlayerLookup
import essential.core.service.chat.ChatService.Companion.conf
import ksp.command.ClientCommand
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Playerc

class Commands {
    @ClientCommand(name = "me", parameter = "<text...>", description = "Chat with special prefix")
    fun me(playerData: PlayerData, arg: Array<out String>) {
        if (playerData.chatMuted) return

        if (conf.blacklist.enabled) {
            val file: Array<String> = rootPath.child("chat_blacklist.txt").readString("UTF-8").split(Regex("\\R")).filter { it.isNotBlank() }.toTypedArray()
            for (s in file) {
                val message = arg[0]
                if ((conf.blacklist.regex && message.matches(s.toRegex())) || (!conf.blacklist.regex && message.contains(s))) {
                    playerData.err("event.chat.blacklisted")
                    return
                }
            }
        }

        val message = arg[0]
        Call.sendMessage("[orange]*[]" + Vars.netServer.chatFormatter.format(playerData.player.`as`(), message))
    }

    @ClientCommand(name = "pm", parameter = "<player> <message...>", description = "Send a private message")
    fun pm(playerData: PlayerData, arg: Array<out String>) {
        if (playerData.chatMuted) return

        val target: Playerc = PlayerLookup.online(arg[0], playerData) ?: return

        if (arg.size > 1) {
            val message = arg[1]
            playerData.player.sendMessage("[green][PM] " + target.plainName() + "[yellow] => [white] " + message)
            target.sendMessage("[blue][PM] [gray][" + playerData.entityId + "][]" + playerData.player.plainName() + "[yellow] => [white] " + message)

            // This part is commented out as it requires access to database and Permission which we don't have proper references for
            /*
            database.getPlayers().stream().filter { p ->
                Permission.INSTANCE.check(p, "pm.other") && p.uuid != player.uuid() && target.uuid() != player.uuid()
            }.forEach { p ->
                p.player.sendMessage("[sky]${player.plainName()}[][yellow] => [pink]${target.plainName()} [white]: ${message}")
            }
            */
        } else {
            playerData.err("command.pm.message")
        }
    }
}
