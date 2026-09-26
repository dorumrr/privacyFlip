package io.github.dorumrr.privacyflip.util

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Two questions that look like one and are not.
 *
 * "Is the phone locked down" is keyguard OR screen-off, and decides whether to apply the user's
 * privacy actions at all. "Can a sensor privacy change still be made" is KeyguardManager
 * .isDeviceLocked alone (a PIN, pattern or password is needed), because that is the only thing
 * Android checks when it refuses one (SensorPrivacyService.canChangeToggleSensorPrivacy).
 *
 * Answering the second with either the first or with "keyguard showing" skips the camera and
 * microphone behind a Swipe lock, during the grace period, and with no lock screen, and then
 * tells the user "device already locked - cannot disable sensors", which is untrue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenLockStateTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun setKeyguard(locked: Boolean) {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(km).setKeyguardLocked(locked)
    }

    private fun setScreenOn(on: Boolean) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIsInteractive(on)
    }

    @Test
    fun `screen off with no keyguard is locked down, but the sensors are still reachable`() {
        // A phone with no secure lock screen, or the grace period before the keyguard engages.
        setKeyguard(false)
        setScreenOn(false)

        assertTrue(
            "the screen being off is still a reason to apply privacy actions",
            isScreenCurrentlyLocked(context, "test")
        )
        assertFalse(
            "but nothing is stopping a sensor privacy change yet, so it must be attempted",
            isDeviceSecurelyLocked(context, "test")
        )
    }

    @Test
    fun `a PIN, pattern or password lock means a sensor privacy change will be refused`() {
        setKeyguard(true)
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).let { shadowOf(it).setIsDeviceLocked(true) }
        setScreenOn(false)

        assertTrue(isScreenCurrentlyLocked(context, "test"))
        assertTrue(
            "once a credential is needed Android refuses the change, and the app must know that",
            isDeviceSecurelyLocked(context, "test")
        )
    }

    @Test
    fun `a phone in use is neither locked down nor out of sensor reach`() {
        setKeyguard(false)
        setScreenOn(true)

        assertFalse(isScreenCurrentlyLocked(context, "test"))
        assertFalse(isDeviceSecurelyLocked(context, "test"))
    }

    @Test
    fun `a Swipe keyguard does not stop a sensor privacy change`() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(km).setKeyguardLocked(true)
        shadowOf(km).setIsDeviceLocked(false)
        setScreenOn(false)

        assertFalse(
            "the keyguard shows but needs no credential, and Android only refuses when one is needed",
            isDeviceSecurelyLocked(context, "test")
        )
    }

    @Test
    fun `an unreadable lock state means the sensors are still tried`() {
        // Failing closed would skip them and show "already locked", which nobody observed.
        shadowOf(context).setSystemService(Context.KEYGUARD_SERVICE, Any())

        assertFalse(isDeviceSecurelyLocked(context, "test"))
    }
}
