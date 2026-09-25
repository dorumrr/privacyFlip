package io.github.dorumrr.privacyflip.receiver

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import io.github.dorumrr.privacyflip.initWorkManagerWithoutRealWork
import io.github.dorumrr.privacyflip.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Checks whether ACTION_SCREEN_ON's "not locked" branch enqueues the unlock-side re-enable, the
 * same way the app's other 3 unlock-detection paths do. Read-through said no. This proves it
 * either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenStateReceiverTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        initWorkManagerWithoutRealWork(context)
    }

    // Counts records in any state: the question is whether it was ever enqueued.
    private fun enqueuedCount(name: String): Int =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().size

    @Test
    fun `ACTION_SCREEN_ON while not locked - does it enqueue the unlock-side re-enable`() {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(false) // confirmed NOT locked

        ScreenStateReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()

        // This used to read 0 (the bug - nothing re-enabled). Watched this exact assertion fail
        // against the pre-fix code, then pass once triggerPrivacyAction was added to this
        // branch.
        assertEquals(
            "ACTION_SCREEN_ON (not locked) must enqueue the unlock-side re-enable, same as the " +
                "app's other 3 unlock-detection paths",
            1,
            enqueuedCount(Constants.Work.NAME_UNLOCK)
        )
    }
}
