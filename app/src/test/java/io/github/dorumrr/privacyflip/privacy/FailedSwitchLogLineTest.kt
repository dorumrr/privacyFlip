package io.github.dorumrr.privacyflip.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The debug log line under a failed switch must say how it was attempted, never "null". */
class FailedSwitchLogLineTest {

    @Test
    fun `a switch made by shell commands names the commands`() {
        assertEquals(
            "❌ Commands attempted: svc nfc disable",
            PrivacyManager.attemptedLine("svc nfc disable")
        )
    }

    // No commands is both the backend-API route and an exception before any result, so the line names neither.
    @Test
    fun `a failed switch with no commands recorded says so instead of null`() {
        val line = PrivacyManager.attemptedLine(null)

        assertFalse(line, line.contains("null"))
        assertEquals("❌ Commands attempted: none recorded", line)
    }
}
