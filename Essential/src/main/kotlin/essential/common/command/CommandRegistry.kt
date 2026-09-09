package essential.common.command

import arc.util.CommandHandler

object CommandRegistry {
    const val PREFIX = "e"

    private val declared = mutableMapOf<String, String>()

    fun resolve(handler: CommandHandler, name: String): String {
        val ours = declared[name] == name
        val taken = !ours && handler.commandList.any { it.text.equals(name, ignoreCase = true) }
        val text = if (taken) PREFIX + name else name
        declared.entries.removeIf { it.value == name }
        declared[text] = name
        return text
    }

    fun canonical(text: String): String = declared[text] ?: text

    fun registered(name: String): String = declared.entries.firstOrNull { it.value == name }?.key ?: name

    /** Every canonical (pre-prefix) plugin command name resolved so far, core and module alike. */
    fun declaredNames(): Set<String> = declared.values.toSet()

    fun clear() = declared.clear()
}
