package io.github.dorumrr.privacyflip.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.dorumrr.privacyflip.initWorkManagerWithoutRealWork
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.TimeUnit

/**
 * Proves cancel()'s Operation result is actually read, not discarded - the log this test checks
 * for only exists because something now listens for the real, asynchronously-resolved outcome
 * instead of assuming success at the call site.
 *
 * Enqueues a REAL NAME_LOCK unique work item first - a version that only ever cancelled a no-op
 * cannot tell "genuinely reading the Operation" apart from "a relabeled synchronous log" - so
 * this exercises the actual cancel-something-real path.
 *
 * Not provable here: whether a genuine Operation FAILURE specifically gets logged as failure -
 * Robolectric's test WorkManager has no way to force cancelUniqueWork() to fail, only to
 * succeed (trivially, even against a no-op). That half stays Verified in code only.
 *
 * The assertion checks for "confirmed" specifically appearing and "FAILED" specifically not
 * appearing, rather than accepting either: only "confirmed" can ever actually occur in this test
 * setup (see the paragraph above), so accepting "FAILED" too would let a bug that swapped the
 * success/failure log branches pass undetected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PendingLockWorkTest {

    private lateinit var context: Application

    // Held WorkManager tasks wait until released, so a cancel's Operation can be seen unresolved.
    private var holdWorkManagerTasks = false
    private val heldTasks = ArrayDeque<Runnable>()

    private fun releaseHeldTasks() {
        holdWorkManagerTasks = false
        while (heldTasks.isNotEmpty()) heldTasks.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        initWorkManagerWithoutRealWork(context) { task ->
            if (holdWorkManagerTasks) heldTasks.addLast(task) else task.run()
        }
        ShadowLog.clear()
    }

    private fun lockStates(): List<WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(Constants.Work.NAME_LOCK).get().map { it.state }

    @Test
    fun `cancel reads the real Operation outcome for a genuinely pending job, not just a no-op`() {
        val tag = "PendingLockWorkTest"

        // A real, pending NAME_LOCK job - not the no-op case.
        val workRequest = OneTimeWorkRequestBuilder<PrivacyActionWorker>()
            .setInputData(workDataOf("is_locking" to true, "is_device_locked" to false))
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            Constants.Work.NAME_LOCK,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the job must still be waiting, or this cancels nothing", listOf(WorkInfo.State.ENQUEUED), lockStates())

        holdWorkManagerTasks = true
        PendingLockWork.cancel(context, tag, Constants.Work.NAME_LOCK)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "the call-site log must be captured, or the next check proves nothing",
            ShadowLog.getLogs().any { it.tag == tag && it.msg.contains("Cancelling pending work") }
        )
        assertTrue(
            "nothing may be confirmed before WorkManager has resolved the cancel",
            ShadowLog.getLogs().none { it.tag == tag && it.msg.contains("cancel confirmed") }
        )

        releaseHeldTasks()
        assertEquals("the waiting job must end cancelled", listOf(WorkInfo.State.CANCELLED), lockStates())

        val logs = ShadowLog.getLogs().filter { it.tag == tag }
        val confirmed = logs.any { it.msg.contains("cancel confirmed") }
        val failed = logs.any { it.msg.contains("cancel FAILED") }

        assertTrue(
            "cancel() must log the Operation's real, resolved outcome for a genuinely pending " +
                "job - not just the immediate call-site attempt - if this fails, the Operation " +
                "result is being discarded again",
            confirmed
        )
        assertFalse(
            "this setup can only ever produce a genuine success - a FAILED log here means the " +
                "success/failure branches were swapped, not a real cancel failure",
            failed
        )
    }
}
