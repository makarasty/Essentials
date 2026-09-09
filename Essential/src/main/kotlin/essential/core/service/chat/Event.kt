package essential.core.service.chat

import essential.common.bundle.Bundle
import essential.common.database.data.PlayerData
import essential.common.permission.Permission
import essential.common.rootPath
import essential.common.util.findPlayerData
import essential.core.service.chat.ChatFormatResolver.resolve
import essential.core.service.chat.ChatService.Companion.conf
import ksp.event.Event
import mindustry.Vars
import mindustry.core.NetServer
import mindustry.game.EventType
import mindustry.gen.Player
import mindustry.net.Administration
import java.util.regex.Pattern

/**
 * The player-independent half of the registered blacklist filter: it decides, it does not reply.
 *
 * The web chat endpoint authenticates a database row rather than a connected player, so it has no
 * `Player` to hand `admins.filterMessage`. It calls this instead, which is what keeps the rule from
 * drifting between the two entry points the way a second inline copy of the list would.
 */
fun isChatBlacklisted(message: String): Boolean {
    if (!conf.blacklist.enabled) return false
    // ChatService writes this file when it starts, so it is missing exactly when the chat module is off.
    // There is no list to match against then, and throwing here would take the caller down with it.
    val file = rootPath.child("chat_blacklist.txt")
    if (!file.exists()) return false
    val entries = file.readString("UTF-8")
        .split(Regex("\\R")).filter { it.isNotBlank() }
    return entries.any {
        if (conf.blacklist.regex) Pattern.compile(it).matcher(message).find() else message.contains(it)
    }
}

@Event
fun serverLoaded(event: EventType.ServerLoadEvent) {
    Vars.netServer.admins.addChatFilter(object : Administration.ChatFilter {
        override fun filter(player: Player, message: String): String? {
            val bundle = Bundle(player.locale)

            if (isChatBlacklisted(message)) {
                player.sendMessage(bundle["event.chat.blacklisted"])
                return null
            }

            return message
        }
    })

    Vars.netServer.chatFormatter = NetServer.ChatFormatter { player, message ->
        if (player != null) {
            val data: PlayerData? = findPlayerData(player.uuid())
            if (message != null) {
                val defaultFormat = "[coral][[" + player.coloredName() + "[coral]]:[white] " + message
                if (data != null) {
                    val chatFormat: String = Permission[data].chatFormat
                    if (chatFormat.isEmpty()) {
                        return@ChatFormatter defaultFormat
                    } else {
                        return@ChatFormatter resolve(chatFormat, data, message)
                    }
                } else {
                    return@ChatFormatter defaultFormat
                }
            }
        }
        return@ChatFormatter null
    }
}