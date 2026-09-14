package io.github.dorumrr.privacyflip.worker

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
