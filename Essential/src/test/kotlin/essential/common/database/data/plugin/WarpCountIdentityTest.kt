package essential.common.database.data.plugin

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PluginData.mergedOnto` identifies elements by `equals`, and the plugin data row is shared by every
 * server. A live player count carried in the constructor was therefore part of that identity, so each
 * refresh read as a delete plus an add and the row grew a duplicate every time two servers were on a
 * same-named map. So is the digit width, which is derived from that count.
 *
 * This is about the identity and the wire format, not about the merge function, which is private. The
 * blob is shared by six servers that upgrade one at a time, so both directions of that window are
 * asserted here rather than discovered on a server.
 */
class WarpCountIdentityTest {
    private val entry get() = WarpCount("hub", 42, "127.0.0.1", 6567)

    @Test
    fun theLiveCountAndItsDigitWidthAreOutsideTheEntrysIdentity() {
        val a = entry
        val b = entry.also {
            it.players = 7
            it.numberSize = 3
        }

        assertEquals(a, b, "a count refresh must not read as a different element")
        assertEquals(a.hashCode(), b.hashCode(), "equal elements must hash equal or `in` is unreliable")
    }

    /**
     * At their default values, which is the case that matters: `Json` omits a property still holding its
     * default, an empty remote server is a count of zero, and a jar predating this change declares both
     * as required constructor fields. Asserting this with a non-zero count would pass while the rollout
     * broke.
     */
    @Test
    fun bothStayOnTheWireAtTheirDefaultsForOlderJars() {
        val encoded = Json.encodeToString(entry)

        assertTrue(encoded.contains("\"players\":0"), "an older jar needs this field to decode the row: $encoded")
        assertTrue(encoded.contains("\"numberSize\":1"), "and this one: $encoded")
    }

    @Test
    fun aBlobWrittenByTheOldJarStillDecodes() {
        val old = """{"mapName":"hub","pos":42,"ip":"127.0.0.1","port":6567,"players":7,"numberSize":3}"""
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val decoded = json.decodeFromString<WarpCount>(old)

        assertEquals(entry, decoded, "an old row must still decode")
        // Asserted separately because neither field is in equals any more, so the line above is blind
        // to exactly the two values this test exists for.
        assertEquals(7, decoded.players, "the stored count must survive the round trip")
        assertEquals(3, decoded.numberSize, "and so must the digit width")
    }
}
