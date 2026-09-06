package essential.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerDescriptionTest {
    private val values = mapOf("players" to { "7" }, "wave" to { "12" }, "peace" to { "" })

    @Test
    fun fills_known_placeholders_and_keeps_unknown_ones() {
        val template = "[#6e7080]\u00ab[#7722dd]PVP[#6e7080]\u00bb {players}/{playerLimit}\\n\u0445\u0432\u0438\u043b\u044f {wave} {unknown}"
        assertEquals(
            "[#6e7080]\u00ab[#7722dd]PVP[#6e7080]\u00bb 7/{playerLimit}\\n\u0445\u0432\u0438\u043b\u044f 12 {unknown}",
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
}
