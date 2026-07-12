package dev.reclaimed.player.homeassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SonosPlaybackStateTest {
    @Test
    fun playingStateAdvancesPositionFromHomeAssistantTimestamp() {
        val updatedAt = Instant.parse("2026-07-12T18:00:00Z")
        val response = JSONObject(
            """
            {
              "state": "playing",
              "attributes": {
                "media_title": "The Greatest",
                "media_artist": "Alabama Shakes",
                "media_position": 67.25,
                "media_position_updated_at": "2026-07-12T18:00:00Z",
                "media_duration": 210.0,
                "volume_level": 0.27
              }
            }
            """.trimIndent(),
        )

        val state = parseSonosPlaybackState(response, updatedAt.toEpochMilli() + 3_000L)

        assertEquals(true, state.isPlaying)
        assertEquals("The Greatest", state.title)
        assertEquals("Alabama Shakes", state.artist)
        assertEquals(70_250L, state.positionMs)
        assertEquals(210_000L, state.durationMs)
        assertEquals(0.27f, state.volumeLevel)
    }

    @Test
    fun pausedStateDoesNotAdvanceAndToleratesMissingOptionalAttributes() {
        val response = JSONObject(
            """{"state":"paused","attributes":{"media_position":12.5}}""",
        )

        val state = parseSonosPlaybackState(response, Long.MAX_VALUE)

        assertEquals(false, state.isPlaying)
        assertEquals(12_500L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertEquals("", state.title)
        assertNull(state.volumeLevel)
    }
}
