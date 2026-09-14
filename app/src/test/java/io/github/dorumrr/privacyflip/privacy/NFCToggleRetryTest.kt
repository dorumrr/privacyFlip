package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.FeatureState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A payment or wallet framework can turn NFC back on moments after this app turns it off. The
 * retry exists for exactly that, and the user has a switch for it on the main screen.
 *
 * The retry used to require the disable attempt to have REPORTED success first. Once the base
 * class began reading the state back, an app fast enough to re-enable NFC before that read made
 * the attempt itself report failure, so the retry never ran in the one case it exists for, and
 * the log still said "NFC successfully disabled (it stayed off)".
 *
 * The attempt's own success flag is therefore passed in and deliberately ignored, so that "it must
 * not matter" is a thing a test can hold rather than a thing a comment claims.
 */
class NFCToggleRetryTest {

    @Test
    fun `the attempt's own success flag must not decide the retry`() {
        // The regression in one line. The old rule was (success AND state == ENABLED), so an
        // override fast enough to make the attempt report failure switched the retry off.
        assertTrue(
            "NFC is on after a disable that reported failure: that is exactly an override",
            NFCToggle.needsRetry(attemptReportedSuccess = false, stateAfterDisable = FeatureState.ENABLED)
        )
        assertTrue(
            "and it is still an override when the attempt reported success",
            NFCToggle.needsRetry(attemptReportedSuccess = true, stateAfterDisable = FeatureState.ENABLED)
        )
    }

    @Test
    fun `NFC reading as off means there is nothing to retry`() {
        assertFalse(NFCToggle.needsRetry(attemptReportedSuccess = true, stateAfterDisable = FeatureState.DISABLED))
        assertFalse(NFCToggle.needsRetry(attemptReportedSuccess = false, stateAfterDisable = FeatureState.DISABLED))
    }

    @Test
    fun `a state that cannot be read is not treated as an override`() {
        // Retrying on an unreadable state would hammer the shell on a device that simply cannot
        // report NFC, and would report a failure nobody observed.
        assertFalse(NFCToggle.needsRetry(attemptReportedSuccess = true, stateAfterDisable = FeatureState.UNKNOWN))
        assertFalse(NFCToggle.needsRetry(attemptReportedSuccess = true, stateAfterDisable = FeatureState.ERROR))
        assertFalse(NFCToggle.needsRetry(attemptReportedSuccess = true, stateAfterDisable = FeatureState.UNAVAILABLE))
    }
}
