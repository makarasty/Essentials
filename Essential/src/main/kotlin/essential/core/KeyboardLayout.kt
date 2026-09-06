package essential.core

import arc.util.CommandHandler
import arc.util.CommandHandler.ResponseType
import mindustry.Vars
import mindustry.core.NetServer
import mindustry.gen.Player
import mindustry.net.Administration
import java.util.concurrent.ConcurrentHashMap

object KeyboardLayout {
    private val toQwerty = mapOf(
        'й' to 'q', 'ц' to 'w', 'у' to 'e', 'к' to 'r', 'е' to 't', 'н' to 'y', 'г' to 'u', 'ш' to 'i', 'щ' to 'o', 'з' to 'p',
        'х' to '[', 'ї' to ']', 'ъ' to ']',
        'ф' to 'a', 'і' to 's', 'ы' to 's', 'в' to 'd', 'а' to 'f', 'п' to 'g', 'р' to 'h', 'о' to 'j', 'л' to 'k', 'д' to 'l',
        'ж' to ';', 'є' to '\'', 'э' to '\'',
        'я' to 'z', 'ч' to 'x', 'с' to 'c', 'м' to 'v', 'и' to 'b', 'т' to 'n', 'ь' to 'm', 'б' to ',', 'ю' to '.',
    )

    private val lastMessage = ConcurrentHashMap<String, String>()

    fun fix(handler: CommandHandler, message: String): String? {
        val prefix = handler.prefix
        val body = when {
            message.startsWith(prefix) -> message.substring(prefix.length)
            message.startsWith(".") -> message.substring(1)
            else -> return null
        }
        val word = body.takeWhile { it != ' ' }
        if (word.none { it.lowercaseChar() in toQwerty }) return null
        val fixed = word.map { toQwerty[it.lowercaseChar()] ?: it }.joinToString("")
        if (handler.commandList.none { it.text.equals(fixed, ignoreCase = true) }) return null
        return prefix + fixed + body.substring(word.length)
    }

    fun remember(player: Player, message: String) {
        lastMessage[player.uuid()] = message
    }

    fun install(handler: CommandHandler = Vars.netServer.clientCommands) {
        Vars.netServer.admins.addChatFilter(Administration.ChatFilter { player, message ->
            val fixed = fix(handler, message) ?: return@ChatFilter message
            handler.handleMessage(fixed, player)
            null
        })

        val fallback = Vars.netServer.invalidHandler
        Vars.netServer.invalidHandler = NetServer.InvalidCommandHandler { player, response ->
            val fixed = if (response.type == ResponseType.unknownCommand) {
                val typed = lastMessage.remove(player.uuid())?.takeIf { it.startsWith(handler.prefix + response.runCommand) }
                fix(handler, typed ?: handler.prefix + response.runCommand)
            } else {
                null
            }
            if (fixed == null) {
                fallback.handle(player, response)
            } else {
                handler.handleMessage(fixed, player)
                null
            }
        }
    }
}
