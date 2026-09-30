package io.github.dorumrr.privacyflip.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slider saves positionToSeconds(position) and the screen then redraws the thumb from
 * secondsToPosition(saved). The thumb must land where that saved value reads back unchanged.
 */
class TimerSettingsTest {

    @Test
    fun `every slider position redraws on a thumb that means the value it saved`() {
        (0..TimerSettings.MAX_POSITION).forEach { position ->
            val saved = TimerSettings.positionToSeconds(position)
            val redrawn = TimerSettings.secondsToPosition(saved)
            assertEquals("position $position", saved, TimerSettings.positionToSeconds(redrawn))
        }
    }
}
