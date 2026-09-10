package io.github.dorumrr.privacyflip.accessibility

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
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

    private fun unlockWorkEnqueuedCount(): Int =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(Constants.Work.NAME_UNLOCK)
            .get()
            .count { !it.state.isFinished }

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

        assertEquals(
            "must enqueue unlock-side work once the keyguard confirms unlocked",
            1,
            unlockWorkEnqueuedCount()
        )
    }
}
