package essential.core.service.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebConfigTest {
    @Test
    fun generatedSessionSecretMeetsMinimumLength() {
        assertTrue(generateSessionSecret().length >= 32)
    }

    /**
     * The field's own default has to be blank. A default is an expression, and kotlinx.serialization
     * runs it every time it fills an absent key - so a generated one minted a new secret whenever
     * `sessionSecret` went missing from config_web.yaml, which Config.load's migration re-save then
     * wrote to disk, signing every open session out. WebService.reloadConf mints it once instead.
     */
    @Test
    fun theDefaultIsBlankRatherThanAFreshSecret() {
        assertEquals("", WebConfig().sessionSecret)
        assertEquals(WebConfig().sessionSecret, WebConfig().sessionSecret)
    }
}
