package essential.core

import PluginTest.Companion.loadGame
import arc.Core
import mindustry.gen.Groups
import mindustry.net.Administration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerDescriptionTest {
    private val values = mapOf("players" to { "7" }, "wave" to { "12" }, "peace" to { "" })

    /** Both keys by their literal names: they live in settings.bin, not in the config. */
    private val templateKey = "essentials-description-template"
    private val renderedKey = "essentials-description-rendered"

    private lateinit var originalConf: CoreConfig
    private lateinit var originalDesc: String

    @BeforeTest
    fun setup() {
        loadGame()
        originalConf = Main.conf
        originalDesc = Administration.Config.desc.string()
    }

    @AfterTest
    fun cleanup() {
        Main.conf = originalConf
        Administration.Config.desc.set(originalDesc)
        Core.settings.remove(templateKey)
        Core.settings.remove(renderedKey)
    }

    /** A server that has never rendered a description, with the operator's `config desc` already set. */
    private fun freshServer(desc: String, template: String = "") {
        Main.conf = Main.conf.copy(
            feature = Main.conf.feature.copy(
                description = Description(enabled = true, template = template, interval = 0)
            )
        )
        Core.settings.remove(templateKey)
        Core.settings.remove(renderedKey)
        Administration.Config.desc.set(desc)
    }

    private fun playersOnline() = "${Groups.player.size()} online"

    @Test
    fun fills_known_placeholders_and_keeps_unknown_ones() {
        val template = "[#6e7080]\u00ab[#7722dd]PVP[#6e7080]\u00bb {players}/{playerLimit}\n\u0445\u0432\u0438\u043b\u044f {wave} {unknown}"
        assertEquals(
            "[#6e7080]\u00ab[#7722dd]PVP[#6e7080]\u00bb 7/{playerLimit}\n\u0445\u0432\u0438\u043b\u044f 12 {unknown}",
            ServerDescription.render(template, values),
        )
    }

    @Test
    fun empty_values_disappear() {
        assertEquals("peace  end", ServerDescription.render("peace {peace} end", values))
    }

    @Test
    fun detects_placeholders() {
        assertTrue(ServerDescription.hasPlaceholders("wave {wave}"))
        assertFalse(ServerDescription.hasPlaceholders("plain text"))
        assertFalse(ServerDescription.hasPlaceholders("json {\"a\": 1}"))
    }

    @Test
    fun placeholder_description_becomes_the_cached_template() {
        freshServer(desc = "{players} online")

        ServerDescription.render()

        assertEquals("{players} online", Core.settings.getString(templateKey, ""))
        assertEquals(playersOnline(), Administration.Config.desc.string())
    }

    @Test
    fun rendered_output_is_not_adopted_as_a_template() {
        // The unknown placeholder survives rendering, so the rendered line looks like a template.
        freshServer(desc = "{players} online {unknown}")
        ServerDescription.render()
        val rendered = Administration.Config.desc.string()
        assertEquals("${playersOnline()} {unknown}", rendered)

        ServerDescription.render()

        assertEquals("{players} online {unknown}", ServerDescription.template())
        assertEquals("{players} online {unknown}", Core.settings.getString(templateKey, ""))
        assertEquals(rendered, Administration.Config.desc.string())
    }

    @Test
    fun operator_replaces_a_cached_template_with_a_plain_description() {
        freshServer(desc = "{players} online")
        ServerDescription.render()
        assertNotEquals("", Core.settings.getString(templateKey, ""))

        Administration.Config.desc.set("Plain text server")
        ServerDescription.render()

        assertEquals("Plain text server", Administration.Config.desc.string())
        assertEquals("Plain text server", Core.settings.getString(templateKey, ""))

        // And it still holds a tick later, which is where the cached template used to come back.
        ServerDescription.render()
        assertEquals("Plain text server", Administration.Config.desc.string())
    }

    @Test
    fun a_plain_description_survives_a_restart() {
        freshServer(desc = "{players} online")
        ServerDescription.render()
        Administration.Config.desc.set("Plain text server")
        ServerDescription.render()

        // A restart: the plugin keeps nothing, everything comes back from settings.bin.
        Core.settings.forceSave()
        Core.settings.loadValues()

        assertEquals("Plain text server", Core.settings.getString(renderedKey, ""))
        ServerDescription.render()
        assertEquals("Plain text server", Administration.Config.desc.string())
        assertEquals("Plain text server", Core.settings.getString(templateKey, ""))
    }

    @Test
    fun the_config_template_still_wins_over_the_cache() {
        freshServer(desc = "{players} online")
        ServerDescription.render()

        Main.conf = Main.conf.copy(
            feature = Main.conf.feature.copy(
                description = Main.conf.feature.description.copy(template = "configured {players}")
            )
        )
        ServerDescription.render()

        assertEquals("configured ${Groups.player.size()}", Administration.Config.desc.string())
    }
}
