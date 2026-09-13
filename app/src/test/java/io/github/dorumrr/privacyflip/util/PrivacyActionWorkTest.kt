package io.github.dorumrr.privacyflip.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The input payload used to be hand-written at four call sites and read back by the worker from
 * its own separate copies of the same key strings. Nothing checked the payload itself: every
 * existing test only counts how many jobs were enqueued, so swapping two values - sending
 * is_device_locked where is_locking belongs - would have gone unnoticed.
 *
 * This covers the contract itself, which all four call sites and the worker now share.
 */
class PrivacyActionWorkTest {

    @Test
    fun `every field reaches the worker under the key the worker actually reads`() {
        val request = PrivacyActionWork.buildRequest(
            isLocking = true,
            isDeviceLocked = false,
            trigger = "screen_state",
            reason = "Screen Off (Unlocked)"
        )

        val input = request.workSpec.input

        // Defaults deliberately opposite to the expected values: a key that never arrived would
        // read back as the default and pass a weaker assertion.
        assertEquals(true, input.getBoolean(PrivacyActionWork.KEY_IS_LOCKING, false))
        assertEquals(false, input.getBoolean(PrivacyActionWork.KEY_IS_DEVICE_LOCKED, true))
        assertEquals("screen_state", input.getString(PrivacyActionWork.KEY_TRIGGER))
        assertEquals("Screen Off (Unlocked)", input.getString(PrivacyActionWork.KEY_REASON))
    }

    @Test
    fun `the two booleans are not swapped`() {
        val request = PrivacyActionWork.buildRequest(
            isLocking = false,
            isDeviceLocked = true,
            trigger = "accessibility_service",
            reason = "Early Unlock Detection (Accessibility)"
        )

        val input = request.workSpec.input

        assertEquals(false, input.getBoolean(PrivacyActionWork.KEY_IS_LOCKING, true))
        assertEquals(true, input.getBoolean(PrivacyActionWork.KEY_IS_DEVICE_LOCKED, false))
    }

    @Test
    fun `lock and unlock use different unique work names, so neither replaces the other`() {
        assertEquals(Constants.Work.NAME_LOCK, PrivacyActionWork.workName(isLocking = true))
        assertEquals(Constants.Work.NAME_UNLOCK, PrivacyActionWork.workName(isLocking = false))

        // The two assertions above both still pass if the constants are ever made equal, and
        // then an unlock's re-enable would REPLACE a live lock job - radios left on after a
        // lock, with nothing going red. This is what pins them apart.
        assertNotEquals(
            "lock and unlock must never share a unique work name",
            PrivacyActionWork.workName(isLocking = true),
            PrivacyActionWork.workName(isLocking = false)
        )
    }

    @Test
    fun `the stored key strings do not change`() {
        // The worker reads these through the same constants, so a rename cannot desynchronise
        // the two halves of the app. What it CAN do is orphan work already queued by an older
        // version across an app upgrade, which reads its payload back by the stored string.
        assertEquals("is_locking", PrivacyActionWork.KEY_IS_LOCKING)
        assertEquals("is_device_locked", PrivacyActionWork.KEY_IS_DEVICE_LOCKED)
        assertEquals("trigger", PrivacyActionWork.KEY_TRIGGER)
        assertEquals("reason", PrivacyActionWork.KEY_REASON)
    }
}
