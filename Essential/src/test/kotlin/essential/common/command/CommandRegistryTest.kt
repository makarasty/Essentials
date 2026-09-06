package essential.common.command

import arc.util.CommandHandler
import essential.core.KeyboardLayout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandRegistryTest {
    @BeforeTest
    fun reset() = CommandRegistry.clear()

    private fun handler(vararg existing: String): CommandHandler {
        val handler = CommandHandler("/")
        existing.forEach { name -> handler.register<Any?>(name, "", "") { _, _ -> } }
        return handler
    }

    @Test
    fun prefixes_only_names_taken_by_someone_else() {
        val handler = handler("vote")

        assertEquals("evote", CommandRegistry.resolve(handler, "vote"))
        assertEquals("maps", CommandRegistry.resolve(handler, "maps"))

        assertEquals("vote", CommandRegistry.canonical("evote"))
        assertEquals("maps", CommandRegistry.canonical("maps"))
        assertEquals("evote", CommandRegistry.registered("vote"))
        assertEquals("unknown", CommandRegistry.registered("unknown"))
    }

    @Test
    fun own_command_keeps_its_name_when_registered_again() {
        val handler = handler()
        handler.register<Any?>(CommandRegistry.resolve(handler, "pm"), "", "") { _, _ -> }

        assertEquals("pm", CommandRegistry.resolve(handler, "pm"))
        assertEquals("pm", CommandRegistry.registered("pm"))
    }

    @Test
    fun cyrillic_layout_maps_to_existing_commands_only() {
        val handler = handler("rtv", "pm")

        assertEquals("/rtv", KeyboardLayout.fix(handler, ".кем"))
        assertEquals("/rtv", KeyboardLayout.fix(handler, "/кем"))
        assertEquals("/rtv", KeyboardLayout.fix(handler, "/КЕМ"))
        assertEquals("/pm Bob привіт", KeyboardLayout.fix(handler, ".зь Bob привіт"))

        assertNull(KeyboardLayout.fix(handler, ".привіт"))
        assertNull(KeyboardLayout.fix(handler, "/rtv"))
        assertNull(KeyboardLayout.fix(handler, "hello"))
        assertNull(KeyboardLayout.fix(handler, "."))
    }
}
