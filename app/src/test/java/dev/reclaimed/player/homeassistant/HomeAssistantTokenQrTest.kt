package dev.reclaimed.player.homeassistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeAssistantTokenQrTest {
    @Test
    fun acceptsRawToken() {
        assertEquals("raw-token", parseHomeAssistantTokenQr("  raw-token  "))
    }

    @Test
    fun extractsJsonToken() {
        assertEquals(
            "json-token",
            parseHomeAssistantTokenQr("""{"access_token":"json-token"}"""),
        )
    }

    @Test
    fun extractsUrlEncodedToken() {
        assertEquals(
            "url token/+",
            parseHomeAssistantTokenQr(
                "homeassistant://pair?token=url+token%2F%2B",
            ),
        )
    }

    @Test
    fun rejectsOrdinaryWebUrl() {
        assertNull(parseHomeAssistantTokenQr("https://homeassistant.local:8123"))
    }
}
