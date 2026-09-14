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
 * Proves a regression this project actually shipped: the first version of the unlock-detection
 * branch fired on ANY window that wasn't lock-screen-classed, with no confirmation of the real
 * keyguard state. A call screen, an alarm, or a dismiss-animation ordering quirk where the
 * window-class event arrives before KeyguardManager's own state catches up would all have looked
 * like an unlock while the phone was genuinely still locked.
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
        // A static, so without this the previous test's lock cycle is still armed and the arming
        // step below quietly does nothing.
        PrivacyAccessibilityService.wasShowingKeyguard = false
    }

    private fun windowEvent(className: String): AccessibilityEvent {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.className = className
        return event
    }

    // Robolectric.setupService() only drives the plain Service lifecycle (onCreate); it does not
    // call onServiceConnected(), which real Android calls via a system binder callback, not part
    // of that plain lifecycle. Without it, isServiceRunning stays false in every test, silently
    // short-circuiting the delayed-recheck guard that checks it - real Android always has
    // onServiceConnected() fire before any accessibility event can arrive, so calling it here
    // matches production ordering, not a workaround for it.
    private fun connectedService(): PrivacyAccessibilityService {
        val service = Robolectric.setupService(PrivacyAccessibilityService::class.java)
        service.onServiceConnected()
        return service
    }

    // Existence, not "not finished": the enqueued CoroutineWorker can genuinely still be RUNNING
    // at one check and SUCCEEDED microseconds later at the next, making a finished-state filter
    // race the worker's own completion. What
    // this test actually needs to know is simpler and race-free: was NAME_UNLOCK ever enqueued
    // at all. shadowOf(Looper.getMainLooper()).idle() after every event lets any queued
    // WorkManager callback settle first, so this reads a stable, already-final answer.
    private fun unlockWorkEnqueuedCount(): Int =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(Constants.Work.NAME_UNLOCK)
            .get()
            .size

    @Test
    fun `repeat keyguard windows within one lock do not re-arm it and restart the lock delay`() {
        // A lock screen emits several keyguard-classed windows: the bouncer, the PIN pad, the
        // shade pulled down on it. Each one used to REPLACE-cancel the job that was waiting out
        // the user's configured lock delay and start that delay again from zero, so a lock screen
        // that kept producing windows postponed the WiFi/Bluetooth/Location disable indefinitely.
        val service = connectedService()

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)

        // Robolectric's elapsedRealtime starts at zero, so move it off zero first or "armed" and
        // "never armed" read the same.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))

        service.onAccessibilityEvent(windowEvent("com.android.systemui.keyguard.KeyguardViewMediator"))
        shadowOf(Looper.getMainLooper()).idle()

        val armedAt = PrivacyActionWorker.lastLockAtMillis
        assertTrue("the first keyguard window must arm the lock", armedAt > 0L)

        // Time passes, as it would while the delay runs, then more keyguard windows arrive.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_000))
        service.onAccessibilityEvent(windowEvent("com.android.systemui.keyguard.KeyguardBouncer"))
        service.onAccessibilityEvent(windowEvent("com.android.systemui.statusbar.phone.KeyguardPINView"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "a repeat window must not re-arm the same lock - re-arming is what restarts the delay",
            armedAt,
            PrivacyActionWorker.lastLockAtMillis
        )
    }

    @Test
    fun `window changes away from lock screen while keyguard still reports locked does not trigger unlock actions`() {
        val service = connectedService()

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true) // real keyguard state: still locked

        // Arm the detector: a lock-screen-classed window first (sets wasShowingKeyguard = true)
        service.onAccessibilityEvent(windowEvent("com.android.systemui.keyguard.KeyguardViewMediator"))

        // A non-lock-screen window now appears, but the keyguard STILL reports locked - the
        // exact race the regression above missed
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
        val service = connectedService()

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
        // Checks whether a missed race is permanent, "no retry or timeout", or whether the
        // keyguard read re-runs on EVERY window event while wasShowingKeyguard stays true, not
        // just once. This proves which of the two is correct.
        val service = connectedService()

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

    @Test
    fun `A3 fix - a missed race is still caught after a delay even with no further window event`() {
        // The narrower defect the self-heal test above doesn't cover: what if NO further window
        // event ever arrives before the device locks again (a glance-and-relock, no navigation)?
        // Advances Robolectric's fake clock with no second window event at all - only the
        // delayed re-check should be able to catch this.
        val service = connectedService()

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)

        service.onAccessibilityEvent(windowEvent("com.android.systemui.keyguard.KeyguardViewMediator"))

        // The race: keyguard state has not caught up yet.
        service.onAccessibilityEvent(windowEvent("com.android.settings.Settings"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("must not fire yet - the race window", 0, unlockWorkEnqueuedCount())

        // Keyguard state catches up, but no further window event is ever sent - only time
        // passing past the re-check delay.
        shadowOf(keyguardManager).setKeyguardLocked(false)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))

        assertEquals(
            "the delayed re-check must catch this even with zero further window events",
            1,
            unlockWorkEnqueuedCount()
        )
    }
}
