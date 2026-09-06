package essential.core

import arc.Core
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
        "wave" to { Vars.state.wave.toString() },
        "map" to { Vars.state.map?.plainName() ?: "" },
        "mode" to { Vars.state.rules.mode().name },
        "playTime" to { playTime },
        "uptime" to { uptime },
    )

    private var timer: Timer.Task? = null
    private var pending = false

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

    fun hasPlaceholders(text: String): Boolean = placeholder.containsMatchIn(text)

    fun render(template: String, values: Map<String, () -> String>): String =
        placeholder.replace(template) { match -> values[match.groupValues[1]]?.invoke() ?: match.value }

    fun render() {
        val template = template()
        if (template.isBlank()) return
        val text = render(template, placeholders)
        if (text != Administration.Config.desc.string()) Administration.Config.desc.set(text)
    }
}
