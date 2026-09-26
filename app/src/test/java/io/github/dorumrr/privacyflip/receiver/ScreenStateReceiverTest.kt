package io.github.dorumrr.privacyflip.receiver

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import io.github.dorumrr.privacyflip.initWorkManagerWithoutRealWork
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.PrivacyActionWork
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
        initWorkManagerWithoutRealWork(context, onRun = { params ->
            if (params.inputData.getBoolean(PrivacyActionWork.KEY_IS_LOCKING, false)) {
                lockJobFlags += params.inputData.keyValueMap[PrivacyActionWork.KEY_IS_DEVICE_LOCKED]
            }
        })
    }

    private fun lockScreen(keyguardShowing: Boolean, needsCredential: Boolean) {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(keyguardShowing)
        shadowOf(keyguardManager).setIsDeviceLocked(needsCredential)
    }

    // Read from each lock job's own input as WorkManager runs it, not from a log line.
    private val lockJobFlags = mutableListOf<Any?>()
    private fun lockJobFlags(): List<Any?> = lockJobFlags

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

    @Test
    fun `screen off behind a Swipe lock still switches camera and microphone`() {
        // Android refuses a sensor privacy change only while a PIN, pattern or password is needed.
        lockScreen(keyguardShowing = true, needsCredential = false)

        ScreenStateReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("one lock job, not marked already locked", listOf<Any?>(false), lockJobFlags())
    }

    @Test
    fun `screen off with no lock screen showing yet still switches camera and microphone`() {
        // No lock screen at all, or the grace period before a timed lock engages.
        lockScreen(keyguardShowing = false, needsCredential = false)
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)

        ScreenStateReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("one lock job, not marked already locked", listOf<Any?>(false), lockJobFlags())
    }

    @Test
    fun `screen off behind a PIN is marked already locked`() {
        lockScreen(keyguardShowing = true, needsCredential = true)

        ScreenStateReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("one lock job, marked, since Android would refuse", listOf<Any?>(true), lockJobFlags())
    }
}
