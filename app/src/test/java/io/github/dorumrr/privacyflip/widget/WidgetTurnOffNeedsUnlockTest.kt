package io.github.dorumrr.privacyflip.widget

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A widget host on the lock screen must not let anyone holding the phone turn protection off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetTurnOffNeedsUnlockTest {

    private lateinit var context: Application
    private lateinit var prefs: PreferenceManager
    private var savedProtection = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferenceManager.getInstance(context)
        savedProtection = prefs.isGlobalPrivacyEnabled
    }

    @After
    fun tearDown() {
        prefs.isGlobalPrivacyEnabled = savedProtection
        setKeyguardLocked(false)
    }

    private fun setKeyguardLocked(locked: Boolean) {
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(locked)
    }

    private fun tapWidget() {
        PrivacyFlipWidgetToggleReceiver().onReceive(
            context,
            Intent(PrivacyFlipWidgetToggleReceiver.ACTION_TOGGLE_PRIVACY)
        )
    }

    @Test
    fun `a tap on a locked phone leaves protection on`() {
        prefs.isGlobalPrivacyEnabled = true
        setKeyguardLocked(true)

        tapWidget()

        assertTrue(prefs.isGlobalPrivacyEnabled)
    }

    @Test
    fun `a tap on an unlocked phone turns protection off`() {
        // The other half: without it, a receiver that ignores every tap would pass the test above.
        prefs.isGlobalPrivacyEnabled = true
        setKeyguardLocked(false)

        tapWidget()

        assertFalse(prefs.isGlobalPrivacyEnabled)
    }

    @Test
    fun `a tap on a locked phone still turns protection on`() {
        prefs.isGlobalPrivacyEnabled = false
        setKeyguardLocked(true)

        tapWidget()

        assertTrue(prefs.isGlobalPrivacyEnabled)
    }
}
