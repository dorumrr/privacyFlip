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
import io.github.dorumrr.privacyflip.util.PrivacyActionWork
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * A screen that wakes for a notification and goes off again, with no unlock, sends a second
 * SCREEN_OFF. It must not restart the lock delay, or a long delay never ends on a busy phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenOffLockDelayTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val lockJobsRun = mutableListOf<Any?>()

    private fun lockedScreen() {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)
        shadowOf(keyguardManager).setIsDeviceLocked(true)
    }

    private fun screenOff() {
        ScreenStateReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun lockWorkIds() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(Constants.Work.NAME_LOCK).get()
            .map { it.id to it.state }

    @Test
    fun `a second screen off keeps the lock job that is still waiting out its delay`() {
        initWorkManagerWithoutRealWork(context, workExecutor = Executor { })
        lockedScreen()

        screenOff()
        val first = lockWorkIds()
        screenOff()

        assertEquals("the waiting lock job must be the same one, not a replacement", first, lockWorkIds())
    }

    @Test
    fun `a screen off after the lock job finished starts a new one`() {
        initWorkManagerWithoutRealWork(context, onRun = { params ->
            if (params.inputData.getBoolean(PrivacyActionWork.KEY_IS_LOCKING, false)) lockJobsRun += true
        })
        lockedScreen()

        screenOff()
        screenOff()

        assertEquals("each finished lock leaves the next screen off free to lock again", 2, lockJobsRun.size)
    }
}
