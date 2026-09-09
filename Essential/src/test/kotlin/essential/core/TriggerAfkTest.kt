package essential.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `feature.afk.server` documents an empty value as "disable teleport" and ships empty, while the
 * reader tested for null, so the shipped configuration took the teleport branch against host "".
 */
class TriggerAfkTest {
    @Test
    fun theShippedEmptyServerMeansKick() {
        // The value an operator gets by enabling feature.afk and touching nothing else.
        assertNull(
            Trigger.afkTarget(CoreConfig().feature.afk.server),
            "the shipped default is documented as disabling the teleport, so it has to select the kick branch"
        )
        assertNull(Trigger.afkTarget(null))
        assertNull(Trigger.afkTarget("   "))
    }

    @Test
    fun aTargetThatCannotBeReachedIsNoTarget() {
        assertNull(Trigger.afkTarget(":6567"), "a value with no host is the same empty host as the default")
        assertNull(Trigger.afkTarget("hub.example.com:"), "the port used to reach toInt and throw")
        assertNull(Trigger.afkTarget("hub.example.com: 80x"))
        assertNull(Trigger.afkTarget("hub.example.com:99999"), "no such port exists")
        assertNull(Trigger.afkTarget("hub.example.com:0"))
    }

    @Test
    fun aConfiguredServerStillTeleports() {
        assertEquals("hub.example.com" to 6567, Trigger.afkTarget("hub.example.com"))
        assertEquals("hub.example.com" to 7000, Trigger.afkTarget("hub.example.com:7000"))
        assertEquals("hub.example.com" to 7000, Trigger.afkTarget("  hub.example.com:7000  "))
        // Unchanged from before the fix: everything past the second segment was already dropped.
        assertEquals("hub.example.com" to 6567, Trigger.afkTarget("hub.example.com:6567:9"))
    }
}
