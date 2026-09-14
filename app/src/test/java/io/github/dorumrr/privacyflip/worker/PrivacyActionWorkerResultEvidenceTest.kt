package io.github.dorumrr.privacyflip.worker

import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.data.PrivacyResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On unlock the app turns protection modes (Airplane Mode, Battery Saver) back off, and then
 * clears the flag that records "this app was the one that turned it on".
 *
 * That clearing used to happen whether the disable WORKED or not. A failed disable therefore told
 * the app the user had set Airplane Mode themselves, so every later unlock took the
 * "was manually set - skipping disable" branch: Airplane Mode stayed on, the app said it was the
 * user's own doing, and restore-on-unlock could never fire again for the life of that setting.
 *
 * The evidence for "it worked" is therefore pinned here, including the case that matters most and
 * reads like success if you are careless: NO results at all.
 */
class PrivacyActionWorkerResultEvidenceTest {

    private fun result(feature: PrivacyFeature, success: Boolean) =
        PrivacyResult(feature = feature, success = success)

    @Test
    fun `no results at all is not evidence that anything worked`() {
        // The dangerous one. An empty list means nothing was reported, and `all {}` on an empty
        // list is TRUE in Kotlin, so the careless spelling would clear the flag on no evidence.
        assertFalse(
            "nothing was reported, so nothing may be concluded",
            PrivacyActionWorker.allSucceeded(emptyList())
        )
    }

    @Test
    fun `a single success counts`() {
        assertTrue(
            PrivacyActionWorker.allSucceeded(listOf(result(PrivacyFeature.AIRPLANE_MODE, true)))
        )
    }

    @Test
    fun `a single failure does not count`() {
        assertFalse(
            "Airplane Mode is still on, so the app must keep owning it",
            PrivacyActionWorker.allSucceeded(listOf(result(PrivacyFeature.AIRPLANE_MODE, false)))
        )
    }

    @Test
    fun `a mode observed ON keeps whatever the app already believed about it`() {
        // THE FIX. Keeping the flag through a failed unlock-disable was not enough on its own:
        // the NEXT lock saw the mode still on, concluded the user must have set it, and handed
        // ownership away. The mode was then stuck on for good and blamed on the user.
        assertTrue(
            "a mode this app failed to turn off is still this app's",
            PrivacyActionWorker.ownershipAfterObserving(FeatureState.ENABLED, currentlyOwned = true)
        )
        assertFalse(
            "and one it never owned is still not its",
            PrivacyActionWorker.ownershipAfterObserving(FeatureState.ENABLED, currentlyOwned = false)
        )
    }

    @Test
    fun `a mode observed OFF ends this app's ownership of it`() {
        // Ownership must not outlive the state it describes: if the mode is off, nothing this app
        // turned on is still on, whoever turned it off.
        assertFalse(
            PrivacyActionWorker.ownershipAfterObserving(FeatureState.DISABLED, currentlyOwned = true)
        )
        assertFalse(
            PrivacyActionWorker.ownershipAfterObserving(FeatureState.DISABLED, currentlyOwned = false)
        )
    }

    @Test
    fun `a state that cannot be read changes nothing either way`() {
        // An unreadable state is not evidence. Acting on it would either disown a mode this app
        // must still turn off, or claim one it never touched.
        listOf(FeatureState.UNKNOWN, FeatureState.ERROR, FeatureState.UNAVAILABLE, null).forEach { state ->
            assertTrue(
                "$state must not disown",
                PrivacyActionWorker.ownershipAfterObserving(state, currentlyOwned = true)
            )
            assertFalse(
                "$state must not claim",
                PrivacyActionWorker.ownershipAfterObserving(state, currentlyOwned = false)
            )
        }
    }

    @Test
    fun `only a mode seen OFF and then switched on belongs to this app`() {
        assertTrue(
            "this app saw it off and turned it on, so it is this app's",
            PrivacyActionWorker.ownershipAfterEnabling(FeatureState.DISABLED, enableSucceeded = true)
        )
        assertFalse(
            "a failed enable turned nothing on",
            PrivacyActionWorker.ownershipAfterEnabling(FeatureState.DISABLED, enableSucceeded = false)
        )
    }

    @Test
    fun `a mode whose state could not be read is never claimed by an enable`() {
        // An enable reports success when its own read-back is unreadable, so "it worked" here is
        // not evidence the mode was off first. Claiming would take a setting of the user's and
        // switch it off at the next unlock, past "Only if not manually set".
        listOf(FeatureState.UNKNOWN, FeatureState.ERROR, FeatureState.UNAVAILABLE, null).forEach { state ->
            assertFalse(
                "$state must not be claimed",
                PrivacyActionWorker.ownershipAfterEnabling(state, enableSucceeded = true)
            )
        }
        assertFalse(
            "and a mode already ON was not turned on by this enable either",
            PrivacyActionWorker.ownershipAfterEnabling(FeatureState.ENABLED, enableSucceeded = true)
        )
    }

    @Test
    fun `one failure among successes still does not count`() {
        assertFalse(
            PrivacyActionWorker.allSucceeded(
                listOf(
                    result(PrivacyFeature.AIRPLANE_MODE, true),
                    result(PrivacyFeature.BATTERY_SAVER, false)
                )
            )
        )
    }
}
