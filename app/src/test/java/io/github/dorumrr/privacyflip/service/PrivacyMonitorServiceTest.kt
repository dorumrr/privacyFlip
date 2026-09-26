package io.github.dorumrr.privacyflip.service

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import io.github.dorumrr.privacyflip.initWorkManagerWithoutRealWork
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.PrivacyActionWork
import io.github.dorumrr.privacyflip.worker.PrivacyActionWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Proves isScreenCurrentlyLocked() must fail CLOSED (assume still locked) when reading real lock
 * state throws, matching every other lock-state read in this app. It used to fail open (assume
 * unlocked) - harmless on its own before this same read got wired into stamping
 * PrivacyActionWorker.lastUnlockAtMillis and cancelling real pending protection, at which point a
 * wrong "unlocked" answer here gained a real, user-visible consequence.
 *
 * isScreenCurrentlyLocked() is private, so this forces the exception it catches (a bad
 * KEYGUARD_SERVICE registration, so the cast inside it throws ClassCastException - a realistic
 * failure, not a synthetic one) and observes the real, public consequence: whether an unlock was
 * recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PrivacyMonitorServiceTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        initWorkManagerWithoutRealWork(context, onRun = { params ->
            if (params.inputData.getBoolean(PrivacyActionWork.KEY_IS_LOCKING, false)) {
                lockJobFlags += params.inputData.keyValueMap[PrivacyActionWork.KEY_IS_DEVICE_LOCKED]
            }
        })
        // Shared companion state - reset so an earlier test in this JVM can never leak a stale
        // timestamp into this one.
        PrivacyActionWorker.lastUnlockAtMillis = 0L
    }

    @Test
    fun `a failed lock-state read must not be recorded as an unlock`() {
        // Force isScreenCurrentlyLocked()'s try block to throw: register something that is not
        // actually a KeyguardManager under KEYGUARD_SERVICE, so the real `as KeyguardManager`
        // cast inside it throws ClassCastException - the same class of failure the function's
        // own catch(e: Exception) is written to handle.
        shadowOf(context).setSystemService(Context.KEYGUARD_SERVICE, Any())

        Robolectric.buildService(PrivacyMonitorService::class.java).create().get()

        // Confirm the test actually reached the code under test, rather than onCreate() bailing
        // out earlier for an unrelated reason and this passing for free - never let silence mean
        // success. Either the lock-side or the unlock-side unique work must exist.
        val anyWorkEnqueued = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(Constants.Work.NAME_LOCK).get().isNotEmpty() ||
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(Constants.Work.NAME_UNLOCK).get().isNotEmpty()
        assertTrue(
            "service init never reached triggerInitialPrivacyAction() - this test proves nothing",
            anyWorkEnqueued
        )

        assertEquals(
            "a failed lock-state read must fail closed (stay locked), not record an unlock",
            0L,
            PrivacyActionWorker.lastUnlockAtMillis
        )
    }

    // Read from each lock job's own input as WorkManager runs it, not from a log line.
    private val lockJobFlags = mutableListOf<Any?>()
    private fun catchUpLockFlags(): List<Any?> = lockJobFlags

    private fun startBehind(keyguardShowing: Boolean, needsCredential: Boolean) {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(keyguardShowing)
        shadowOf(keyguardManager).setIsDeviceLocked(needsCredential)
        Robolectric.buildService(PrivacyMonitorService::class.java).create().get()
    }

    @Test
    fun `the start-up catch-up behind a Swipe lock still switches camera and microphone`() {
        startBehind(keyguardShowing = true, needsCredential = false)

        assertEquals("one catch-up lock job, not marked already locked", listOf<Any?>(false), catchUpLockFlags())
    }

    @Test
    fun `the start-up catch-up behind a PIN is marked already locked`() {
        startBehind(keyguardShowing = true, needsCredential = true)

        assertEquals("one catch-up lock job, marked, since Android would refuse", listOf<Any?>(true), catchUpLockFlags())
    }
}
