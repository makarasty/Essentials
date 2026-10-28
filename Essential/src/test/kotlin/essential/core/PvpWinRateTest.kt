package essential.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** /ranking pvp once divided wins by losses plus eliminations, so 109 wins and 74 losses read 114%. */
class PvpWinRateTest {
    @Test
    fun shareOfDecidedGames() {
        assertEquals(59, pvpWinRate(109, 74))
        assertEquals(88, pvpWinRate(16, 2))
        assertEquals(0, pvpWinRate(0, 0))
        assertEquals(0, pvpWinRate(0, 5))
        assertEquals(100, pvpWinRate(3, 0))
        assertEquals(99, pvpWinRate(199, 1))
    }
}
