package io.github.dorumrr.privacyflip.accessibility

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.dorumrr.privacyflip.util.Constants
import io.github.dorumrr.privacyflip.util.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Proves the #C3 regression this project actually shipped and caught by adversarial review
 * (09 Sep round) rather than by a test: the first version of the unlock-detection branch fired
 * on ANY window that wasn't lock-screen-classed, with no confirmation of the real keyguard
 * state. A call screen, an alarm, or a dismiss-animation ordering quirk where the window-class
 * event arrives before KeyguardManager's own state catches up would all have looked like an
 * unlock while the phone was genuinely still locked.
 *
 * Runs the real service class against Robolectric's fake Android framework - no emulator, no
 * device - so this fact stays provable by running a command, not by re-reading the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyAccessibilityServiceTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        PreferenceManager.getInstance(context).accessibilityServiceEnabled = true
    }

    private fun windowEvent(className: String): AccessibilityEvent {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.className = className
        return event
    }

    // Existence, not "not finished" (found 10 Sep, /phi:debug pass - reviewer 3 challenged the
    // original !isFinished filter, and a direct check proved it right to challenge: the enqueued
    // CoroutineWorker can genuinely still be RUNNING at one check and SUCCEEDED microseconds
    // later at the next, making a finished-state filter race the worker's own completion. What
    // this test actually needs to know is simpler and race-free: was NAME_UNLOCK ever enqueued
    // at all. shadowOf(Looper.getMainLooper()).idle() after every event lets any queued
    // WorkManager callback settle first, so this reads a stable, already-final answer.
    private fun unlockWorkEnqueuedCount(): Int =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(Constants.Work.NAME_UNLOCK)
            .get()
            .size

    @Test
    fun `window changes away from lock screen while keyguard still reports locked does not trigger unlock actions`() {
        val service = Robolectric.setupService(PrivacyAccessibilityService::class.java)

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true) // real keyguard state: still locked

        // Arm the detector: a lock-screen-classed window first (sets wasShowingKeyguard = true)
        service.onAccessibilityEvent(windowEvent("com.android.systemui.keyguard.KeyguardViewMediator"))

        // A non-lock-screen window now appears, but the keyguard STILL reports locked - the
        // exact race the #C3 regression missed
        service.onAccessibilityEvent(windowEvent("com.android.settings.Settings"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "must not enqueue unlock-side work while the keyguard still reports locked",
            0,
            unlockWorkEnqueuedCount()
        )
    }

    @Test
    fun `window changes away from lock screen once keyguard confirms unlocked does trigger unlock actions`() {
        val service = Robolectric.setupService(PrivacyAccessibilityService::class.java)

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)

        service.onAccessibilityEvent(windowEvent("com.android.systemui.keyguard.KeyguardViewMediator"))

        shadowOf(keyguardManager).setKeyguardLocked(false) // genuine unlock this time
        service.onAccessibilityEvent(windowEvent("com.android.settings.Settings"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "must enqueue unlock-side work once the keyguard confirms unlocked",
            1,
            unlockWorkEnqueuedCount()
        )
    }

    @Test
    fun `a missed race self-heals on the next window event once keyguard catches up`() {
        // Checks PLAN.md's H3/A3 (found 10 Sep, /phi:debug pass): the finding claimed a missed
        // race is permanent, "no retry or timeout". Closer reading suggested otherwise - the
        // keyguard read re-runs on EVERY window event while wasShowingKeyguard stays true, not
        // just once. This proves which reading is correct.
        val service = Robolectric.setupService(PrivacyAccessibilityService::class.java)

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)

        service.onAccessibilityEvent(windowEvent("com.android.systemui.keyguard.KeyguardViewMediator"))

        // First non-lock-screen event: the race. Keyguard state has not caught up yet.
        service.onAccessibilityEvent(windowEvent("com.android.settings.Settings"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("must not fire yet - the race window", 0, unlockWorkEnqueuedCount())

        // Keyguard state catches up; a second, later window event arrives (any further
        // navigation on the phone produces one of these in real use).
        shadowOf(keyguardManager).setKeyguardLocked(false)
        service.onAccessibilityEvent(windowEvent("com.android.settings.SubSettings"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "H3: does a later window event recover the missed unlock, or does it stay lost " +
                "forever as the finding claimed",
            1,
            unlockWorkEnqueuedCount()
        )
    }
}
