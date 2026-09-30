package io.github.dorumrr.privacyflip.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A feature the privilege backend cannot switch (Wi-Fi on Dhizuku) failed on every lock while its
 * switch said it was protected. It must not be asked for, and the stored choice must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackendUnsupportedFeaturesTest {

    private lateinit var preferenceManager: PreferenceManager
    private val cannotSwitch = setOf(PrivacyFeature.WIFI, PrivacyFeature.MOBILE_DATA)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        preferenceManager = PreferenceManager.getInstance(context)
        preferenceManager.getRawPreferences().edit().clear().commit()
        PrivacyFeature.values().forEach {
            preferenceManager.setFeatureDisableOnLock(it, true)
            preferenceManager.setFeatureEnableOnUnlock(it, true)
        }
    }

    @Test
    fun `features the backend cannot switch are left out of lock and unlock`() {
        val configManager = FeatureConfigurationManager(preferenceManager, backendCanSwitch = { it !in cannotSwitch })

        val onLock = configManager.getFeaturesToDisableOnLock()
        val onUnlock = configManager.getFeaturesToEnableOnUnlock()

        cannotSwitch.forEach {
            assertFalse("${it.displayName} on lock", it in onLock)
            assertFalse("${it.displayName} on unlock", it in onUnlock)
        }
        assertEquals(PrivacyFeature.values().size - cannotSwitch.size, onLock.size)
        assertTrue("the stored choice is the user's", preferenceManager.getFeatureDisableOnLock(PrivacyFeature.WIFI))
    }

    @Test
    fun `a feature whose unlock result the backend fixes is not asked for at unlock, only at lock`() {
        // Dhizuku: unlock can never turn Bluetooth on, so asking for it only reports a switch that did not happen.
        val configManager = FeatureConfigurationManager(
            preferenceManager,
            unlockDecides = { it != PrivacyFeature.BLUETOOTH }
        )

        assertFalse(PrivacyFeature.BLUETOOTH in configManager.getFeaturesToEnableOnUnlock())
        assertTrue(PrivacyFeature.BLUETOOTH in configManager.getFeaturesToDisableOnLock())
    }
}
