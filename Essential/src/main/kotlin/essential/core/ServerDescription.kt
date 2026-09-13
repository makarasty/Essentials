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
    private const val RENDERED_KEY = "essentials-description-rendered"
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
    private var cacheNoted: String? = null

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

    /**
     * The text [render] last wrote to the description.
     *
     * Kept in settings rather than in a field, so a restart does not make this object's own
     * output from before it look like something an operator typed. The first call on a server
     * that upgraded from a build without the key assumes exactly that: whatever is in the
     * description right now is ours, because adopting a rendered line as the template would
     * freeze every value in it.
     */
    private fun lastRendered(): String {
        if (!Core.settings.has(RENDERED_KEY)) Core.settings.put(RENDERED_KEY, Administration.Config.desc.string())
        return Core.settings.getString(RENDERED_KEY, "")
    }

    fun template(): String {
        val current = Administration.Config.desc.string()
        // Anything in the description that this object did not write itself is an operator's
        // `config desc`, placeholders or not - a plain description has to be able to replace a
        // template that was cached earlier, and comparing against the last rendered text is what
        // tells the two apart.
        if (hasPlaceholders(current) || (current.isNotBlank() && current != lastRendered())) {
            Core.settings.put(TEMPLATE_KEY, current)
            return current
        }
        val configured = conf.feature.description.template
        if (configured.isNotBlank()) return configured
        val cached = Core.settings.getString(TEMPLATE_KEY, "")
        // The cache lives in settings.bin, not in config.yaml, so say where the description
        // nobody configured comes from and how to replace it.
        if (cached.isNotBlank() && cached != cacheNoted) {
            cacheNoted = cached
            Log.info(
                "[Description] feature.description.template is blank, using the template remembered " +
                    "from an earlier `config desc`: $cached - run `config desc <text>` to replace it."
            )
        }
        return cached
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
        if (text != lastRendered()) Core.settings.put(RENDERED_KEY, text)
    }
}
