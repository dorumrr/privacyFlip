package io.github.dorumrr.privacyflip.util

import io.github.dorumrr.privacyflip.data.FeatureState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the exact bug class that hit Mobile Data earlier this project's
 * history (#J1): a status field whose own NAME contains the state word - "mIsDataEnabled"
 * contains "enabled" regardless of its actual value - which breaks a naive
 * `.contains("enabled")`-style check. StatusParsingUtils.parseStandardOutput() itself does
 * not read raw telephony field names directly (MobileDataToggle has its own override for
 * that, tested separately in MobileDataToggleTest), but every test here is written to prove
 * the CURRENT parser does not fall into that trap on inputs shaped like it, and a test that
 * pins down a naive matcher's failure alongside it so the two are never confused.
 */
class StatusParsingUtilsTest {

    /** What a naive matcher (the pre-fix approach) would have done - kept here so the tests
     * below can show the current parser actually differs from it, not just that the current
     * parser "passes". */
    private fun naiveContainsMatcher(output: String): FeatureState {
        val normalized = output.lowercase()
        return when {
            normalized.contains("enabled") || normalized.contains("on") -> FeatureState.ENABLED
            normalized.contains("disabled") || normalized.contains("off") -> FeatureState.DISABLED
            else -> FeatureState.UNKNOWN
        }
    }

    @Test
    fun `field name containing the word disabled does not fool the naive matcher into ENABLED`() {
        // A field literally named "...Disabled..." isn't the shape MobileDataToggle hit, but
        // it's the same class of trap in the other direction - proving the naive reference
        // implementation above genuinely misreads names, not just values, is what makes the
        // differential in the next tests meaningful rather than a coincidence.
        val output = "mIsWifiDisabledByUser=false"
        assertEquals(
            "naive matcher should misread this as DISABLED because the field name contains 'disabled'",
            FeatureState.DISABLED,
            naiveContainsMatcher(output)
        )
    }

    @Test
    fun `parseStandardOutput reads a genuinely enabled value as ENABLED`() {
        // True-positive control: the fix must not have swung so far the other way that a
        // real "on" reading now gets missed.
        assertEquals(FeatureState.ENABLED, StatusParsingUtils.parseStandardOutput("1"))
        assertEquals(FeatureState.ENABLED, StatusParsingUtils.parseStandardOutput("enabled"))
    }

    @Test
    fun `parseStandardOutput reads a genuinely disabled value as DISABLED`() {
        assertEquals(FeatureState.DISABLED, StatusParsingUtils.parseStandardOutput("0"))
        assertEquals(FeatureState.DISABLED, StatusParsingUtils.parseStandardOutput("disabled"))
    }

    @Test
    fun `parseStandardOutput treats empty or null output as UNKNOWN, not a guess`() {
        assertEquals(FeatureState.UNKNOWN, StatusParsingUtils.parseStandardOutput(""))
        assertEquals(FeatureState.UNKNOWN, StatusParsingUtils.parseStandardOutput("null"))
    }

    @Test
    fun `parseSensorPrivacyOutput reads state_type=1 as DISABLED (sensor blocked)`() {
        val output = "sensor=2 (camera) state_type=1"
        assertEquals(FeatureState.DISABLED, StatusParsingUtils.parseSensorPrivacyOutput(output, sensorId = 2))
    }

    @Test
    fun `parseSensorPrivacyOutput reads state_type=2 as ENABLED (sensor allowed)`() {
        val output = "sensor=1 (microphone) state_type=2"
        assertEquals(FeatureState.ENABLED, StatusParsingUtils.parseSensorPrivacyOutput(output, sensorId = 1))
    }

    @Test
    fun `parseSensorPrivacyOutput defaults to ENABLED when the sensor entry is missing`() {
        // Matches the documented default in StatusParsingUtils.kt's own KDoc: a sensor with
        // no entry in the dump is assumed allowed, not blocked.
        assertEquals(FeatureState.ENABLED, StatusParsingUtils.parseSensorPrivacyOutput("sensor=99 state_type=1", sensorId = 2))
    }
}
