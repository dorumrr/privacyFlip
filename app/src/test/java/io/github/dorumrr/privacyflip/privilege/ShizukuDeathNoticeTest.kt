package io.github.dorumrr.privacyflip.privilege

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.PreferenceManager
import io.github.dorumrr.privacyflip.util.PrivilegeLossNotice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shizuku dying stops the monitor service, so no lock is left to report the loss: the death must. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuDeathNoticeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = PreferenceManager.getInstance(context)

    @Before
    fun setUp() {
        prefs.privilegeMissingReason = null
    }

    @After
    fun tearDown() {
        prefs.privilegeMissingReason = null
    }

    @Test
    fun `Shizuku dying tells the user, though the service it stops runs no more locks`() {
        val executor = ShizukuExecutor().apply { binderAlive = { false } }
        try {
            runBlocking { executor.initialize(context) }
            assertNull("nothing may be stored before the death", prefs.privilegeMissingReason)

            executor.onBinderDead()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            fun shown() = shadowOf(manager).allNotifications.any { it.channelId == Constants.AccessNotification.CHANNEL_ID }
            // The reason is stored before the notice is posted, on the executor's own thread.
            val end = System.currentTimeMillis() + 3_000
            while (!shown() && System.currentTimeMillis() < end) Thread.sleep(20)
            assertEquals(PrivilegeLossNotice.SHIZUKU_STOPPED, prefs.privilegeMissingReason)
            assertTrue("and it is shown, not only stored", shown())
        } finally {
            executor.cleanup()
        }
    }

    @Test
    fun `a death handled after Shizuku is already back changes nothing`() {
        val checked = CountDownLatch(1)
        val executor = ShizukuExecutor().apply { binderAlive = { checked.countDown(); true } }
        try {
            runBlocking { executor.initialize(context) }

            executor.onBinderDead()

            assertTrue("the death handler must have run and looked", checked.await(3, TimeUnit.SECONDS))
            Thread.sleep(200)

            assertNull("Shizuku is back, so nothing may say it stopped", prefs.privilegeMissingReason)
        } finally {
            executor.cleanup()
        }
    }
}
