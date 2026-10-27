package io.github.dorumrr.privacyflip.tile

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Quick Settings opens on the lock screen. Turning protection off there must go through the
 * unlock prompt, or anyone holding a locked phone can switch it off, and with it Keep Off While
 * Locked. ShadowTileService.unlockAndRun() unlocks and runs at once, so "it unlocked" is read
 * from isLocked going false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TileTurnOffNeedsUnlockTest {

    private lateinit var prefs: PreferenceManager
    private var savedProtection = true

    @Before
    fun setUp() {
        prefs = PreferenceManager.getInstance(ApplicationProvider.getApplicationContext<Application>())
        savedProtection = prefs.isGlobalPrivacyEnabled
    }

    @After
    fun tearDown() {
        prefs.isGlobalPrivacyEnabled = savedProtection
    }

    private fun tapTile(locked: Boolean): PrivacyFlipTileService {
        val service = Robolectric.buildService(PrivacyFlipTileService::class.java).create().get()
        shadowOf(service).setLocked(locked)
        service.onClick()
        shadowOf(Looper.getMainLooper()).idle()
        return service
    }

    @Test
    fun `on a locked phone the tile turns protection off only through the unlock prompt`() {
        prefs.isGlobalPrivacyEnabled = true

        val service = tapTile(locked = true)

        assertFalse("the tile must ask for an unlock before turning protection off", service.isLocked)
        assertFalse("and once unlocked it does turn it off", prefs.isGlobalPrivacyEnabled)
    }

    @Test
    fun `on an unlocked phone the tile turns protection off at once`() {
        prefs.isGlobalPrivacyEnabled = true

        tapTile(locked = false)

        assertFalse(prefs.isGlobalPrivacyEnabled)
    }

    @Test
    fun `turning protection on from the lock screen needs no unlock`() {
        prefs.isGlobalPrivacyEnabled = false

        val service = tapTile(locked = true)

        assertTrue("turning protection on cannot hurt the owner, so no prompt", service.isLocked)
        assertTrue(prefs.isGlobalPrivacyEnabled)
    }
}
