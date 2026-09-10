package io.github.dorumrr.privacyflip.receiver

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.dorumrr.privacyflip.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Checks PLAN.md's H1/A1 (found 10 Sep, /phi:debug pass): does ACTION_SCREEN_ON's "not locked"
 * branch enqueue the unlock-side re-enable, the same way the app's other 3 unlock-detection
 * paths do? Read-through said no. This proves it either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenStateReceiverTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    // Existence, not "not finished" - see PrivacyAccessibilityServiceTest's own comment on this
    // same choice, found necessary this round after a direct check showed a finished-state
    // filter can race the enqueued CoroutineWorker's own completion.
    private fun enqueuedCount(name: String): Int =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().size

    @Test
    fun `ACTION_SCREEN_ON while not locked - does it enqueue the unlock-side re-enable`() {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(false) // confirmed NOT locked

        ScreenStateReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()

        // H1 predicts this is 0 (the bug: nothing re-enables). If the code turns out to already
        // enqueue it, this assertion itself fails and H1 is dead, not confirmed.
        assertEquals(
            "H1: ACTION_SCREEN_ON (not locked) should enqueue unlock-side work if the app is " +
                "meant to fully recover here - recording what actually happens, not assuming it",
            0,
            enqueuedCount(Constants.Work.NAME_UNLOCK)
        )
    }
}
