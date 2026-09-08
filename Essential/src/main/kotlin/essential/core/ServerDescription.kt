package essential.core

import arc.Core
import arc.util.Log
import arc.util.Timer
import essential.common.playTime
import essential.common.uptime
import essential.core.Main.Companion.conf
import mindustry.Vars
import mindustry.gen.Groups
import mindustry.net.Administration

object ServerDescription {
    private const val TEMPLATE_KEY = "essentials-description-template"
    private val placeholder = Regex("\\{([a-zA-Z]+)}")

    val placeholders: MutableMap<String, () -> String> = mutableMapOf(
        "players" to { Groups.player.size().toString() },
        "playerLimit" to { Vars.netServer.admins.playerLimit.toString() },
        // A map without waves always reports wave 1, which says nothing; show nothing instead.
        "wave" to { if (Vars.state.rules.waves) Vars.state.wave.toString() else "" },
        "map" to { Vars.state.map?.plainName() ?: "" },
        "mode" to { Vars.state.rules.mode().name },
        "playTime" to { playTime },
        "matchTime" to { MatchClock.text },
        "uptime" to { uptime },
        // Filled in by the protect service; empty when that module is off, so the
        // placeholder never survives into the server list as literal text.
        "peace" to { "" },
    )

    private const val DESC_LIMIT = 300

    private var timer: Timer.Task? = null
    private var pending = false
    private var tooLongWarned: String? = null

    fun start() {
        timer?.cancel()
        timer = null
        val settings = conf.feature.description
        if (!settings.enabled) return
        if (settings.interval > 0) {
            timer = Timer.schedule({ render() }, 0f, settings.interval.toFloat())
        } else {
            render()
        }
    }

    fun changed() {
        val settings = conf.feature.description
        if (!settings.enabled || !settings.updateOnChange || pending) return
        pending = true
        Core.app.post {
            pending = false
            render()
        }
    }

    fun template(): String {
        val current = Administration.Config.desc.string()
        if (hasPlaceholders(current)) {
            Core.settings.put(TEMPLATE_KEY, current)
            return current
        }
        return conf.feature.description.template.ifBlank { Core.settings.getString(TEMPLATE_KEY, "") }
    }

    /**
     * Only placeholders this engine knows count. An unknown one is left in the text by
     * [render], and if it counted, the rendered text would be read back as the template
     * on the next tick - freezing every other value into the description for good.
     */
    fun hasPlaceholders(text: String): Boolean =
        placeholder.findAll(text).any { it.groupValues[1] in placeholders }

    fun render(template: String, values: Map<String, () -> String>): String =
        placeholder.replace(template) { match -> values[match.groupValues[1]]?.invoke() ?: match.value }

    fun render() {
        MatchClock.update()
        val template = template()
        if (template.isBlank()) return
        val text = render(template, placeholders)
        if (text.length > DESC_LIMIT && text != tooLongWarned) {
            tooLongWarned = text
            Log.warn(
                "[Description] ${text.length} characters, the server list cuts it at $DESC_LIMIT " +
                    "(100 on an unpatched client). Colour tags count too."
            )
        }
        if (text != Administration.Config.desc.string()) Administration.Config.desc.set(text)
    }
}
