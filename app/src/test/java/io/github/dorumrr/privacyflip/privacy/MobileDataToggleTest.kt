package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.root.RootManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the exact bug this project hit (#J1): the telephony status line's own
 * field NAME contains the word "enabled" ("mIsDataEnabled") regardless of its actual value,
 * which breaks a naive `.contains("enabled")`-style check. MobileDataToggle.parseStatusOutput()
 * checks the value after the '=' explicitly instead - these tests prove that differential is
 * real, not just read from the code.
 *
 * RootManager.getInstance(Unit) is safe to call here: its constructor touches no Android
 * framework API, only initialize() (never called in this test) does.
 */
class MobileDataToggleTest {

    private val toggle = MobileDataToggle(RootManager.getInstance(Unit))

    /** What a naive matcher would have done - see StatusParsingUtilsTest for the same pattern. */
    private fun naiveContainsMatcher(output: String): FeatureState {
        val normalized = output.lowercase()
        return when {
            normalized.contains("enabled") || normalized.contains("on") -> FeatureState.ENABLED
            normalized.contains("disabled") || normalized.contains("off") -> FeatureState.DISABLED
            else -> FeatureState.UNKNOWN
        }
    }

    @Test
    fun `the naive matcher genuinely misreads a disabled telephony line as ENABLED`() {
        // Proves the trap this fix avoids is real, not hypothetical - "mIsDataEnabled" contains
        // "enabled" as a substring of the FIELD NAME even when the value is false.
        assertEquals(
            "the field name alone should fool a naive contains() check",
            FeatureState.ENABLED,
            naiveContainsMatcher("mIsDataEnabled=false")
        )
    }

    @Test
    fun `parseStatusOutput correctly reads a disabled telephony line as DISABLED`() {
        assertEquals(FeatureState.DISABLED, toggle.parseStatusOutput("mIsDataEnabled=false"))
    }

    @Test
    fun `parseStatusOutput correctly reads an enabled telephony line as ENABLED`() {
        // True-positive control: a genuinely-on value must still read as on.
        assertEquals(FeatureState.ENABLED, toggle.parseStatusOutput("mIsDataEnabled=true"))
    }

    @Test
    fun `parseStatusOutput reads the telephony line even with real device leading whitespace`() {
        // The exact shape confirmed live against a real phone this session:
        // "    mIsDataEnabled=true" (dumpsys indents its output).
        assertEquals(FeatureState.ENABLED, toggle.parseStatusOutput("    mIsDataEnabled=true"))
    }

    @Test
    fun `parseStatusOutput falls back to the generic parser when there is no telephony line`() {
        // Confirms the fallback path (StatusParsingUtils.parseStandardOutput) still works when
        // the telephony command found nothing and executeWithFallbacks moved on to the plain
        // settings flag, whose output is just "1" or "0", not a telephony-style line.
        assertEquals(FeatureState.ENABLED, toggle.parseStatusOutput("1"))
        assertEquals(FeatureState.DISABLED, toggle.parseStatusOutput("0"))
    }
}
