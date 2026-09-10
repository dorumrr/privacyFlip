package io.github.dorumrr.privacyflip.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Proves #A4 (PLAN.md, confirmed 10 Sep by /phi:debug): cancel()'s Operation result is actually
 * read, not discarded - the log this test checks for only exists because something now listens
 * for the real, asynchronously-resolved outcome instead of assuming success at the call site.
 *
 * Enqueues a REAL NAME_LOCK unique work item first (found necessary this round - the first draft
 * only ever cancelled a no-op, which cannot tell "genuinely reading the Operation" apart from
 * "a relabeled synchronous log"), so this exercises the actual cancel-something-real path.
 *
 * Not provable here: whether a genuine Operation FAILURE specifically gets logged as failure -
 * Robolectric's test WorkManager has no way to force cancelUniqueWork() to fail, only to
 * succeed (trivially, even against a no-op). That half of A4 stays Verified in code only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PendingLockWorkTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        ShadowLog.clear()
    }

    @Test
    fun `cancel reads the real Operation outcome for a genuinely pending job, not just a no-op`() {
        val tag = "PendingLockWorkTest"

        // A real, pending NAME_LOCK job - not the no-op case.
        val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
            .setInputData(workDataOf("is_locking" to true, "is_device_locked" to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            Constants.Work.NAME_LOCK,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        shadowOf(Looper.getMainLooper()).idle()

        PendingLockWork.cancel(context, tag)
        shadowOf(Looper.getMainLooper()).idle()

        val logs = ShadowLog.getLogs().filter { it.tag == tag }
        val confirmedOrFailed = logs.any {
            it.msg.contains("cancel confirmed") || it.msg.contains("cancel FAILED")
        }

        assertTrue(
            "cancel() must log the Operation's real, resolved outcome for a genuinely pending " +
                "job (confirmed or failed), not just the immediate call-site attempt - if this " +
                "fails, the Operation result is being discarded again",
            confirmedOrFailed
        )
    }
}
