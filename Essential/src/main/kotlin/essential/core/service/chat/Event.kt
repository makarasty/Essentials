package essential.core.service.chat

import arc.files.Fi
import arc.util.Log
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
import java.util.regex.PatternSyntaxException

// Compiling every entry and re-reading the file happened inside filter(), so it happened again for
// every chat message on the server (task-106). Cached here instead, keyed on the file's mtime and size
// the way MapController already caches a map hash - a stat is orders of magnitude cheaper than the read
// plus N Pattern.compile calls it replaces, and it is enough to notice an operator's edit without a
// watcher: the file is checked on every call, just no longer re-read and re-compiled on every call.
private class CompiledBlacklist(val lastModified: Long, val size: Long, val regex: Boolean, val matchers: List<(String) -> Boolean>)

@Volatile
private var blacklistCache: CompiledBlacklist? = null

private fun compiledBlacklist(file: Fi): List<(String) -> Boolean> {
    val lastModified = file.lastModified()
    val size = file.length()
    val regex = conf.blacklist.regex
    val cached = blacklistCache
    if (cached != null && cached.lastModified == lastModified && cached.size == size && cached.regex == regex) {
        return cached.matchers
    }
    val entries = file.readString("UTF-8").split(Regex("\\R")).filter { it.isNotBlank() }
    val matchers: List<(String) -> Boolean> = if (regex) {
        entries.mapNotNull { text ->
            try {
                val pattern = Pattern.compile(text)
                val matcher: (String) -> Boolean = { message -> pattern.matcher(message).find() }
                matcher
            } catch (_: PatternSyntaxException) {
                // One bad line used to throw PatternSyntaxException per message and take the whole
                // filter down with it. Skipped instead, once, with the entry so the operator can find it -
                // not a line number: entries is already blank-filtered above, so its index would not
                // match the file's physical lines. An operator's own typo is a warning, not an engine
                // error - Log.err aborts a listener dispatch (arc.Events.fire has no try/catch) and this
                // harness fails a test on any err log.
                Log.warn("chat_blacklist.txt: invalid regex '@', ignoring this entry", text)
                null
            }
        }
    } else {
        entries.map { text -> { message: String -> message.contains(text) } }
    }
    val result = CompiledBlacklist(lastModified, size, regex, matchers)
    blacklistCache = result
    return matchers
}

private fun blacklistFile(): Fi = rootPath.child("chat_blacklist.txt")

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
    val file = blacklistFile()
    if (!file.exists()) return false
    return compiledBlacklist(file).any { it(message) }
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