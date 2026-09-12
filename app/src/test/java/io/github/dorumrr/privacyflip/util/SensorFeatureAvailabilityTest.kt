package io.github.dorumrr.privacyflip.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Camera and microphone are driven by `cmd sensor_privacy`, which only exists on Android 12
 * and up (CameraToggle/MicrophoneToggle). Below that the command is not there, so asking for
 * them on lock is a request that can never be carried out: the worker used to include them,
 * run the command, and fail on every single lock, while the switches stayed on and told the
 * user they were protected. Both defaults are ON (Constants.Defaults), so this was the
 * out-of-the-box state on those versions, not an unusual setting.
 */
@RunWith(RobolectricTestRunner::class)
class SensorFeatureAvailabilityTest {

    private lateinit var configManager: FeatureConfigurationManager
    private lateinit var preferenceManager: PreferenceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        preferenceManager = PreferenceManager.getInstance(context)
        preferenceManager.getRawPreferences().edit().clear().commit()
        configManager = FeatureConfigurationManager(preferenceManager)
        // Ask for everything, so nothing below passes just because it was never requested.
        PrivacyFeature.values().forEach {
            preferenceManager.setFeatureDisableOnLock(it, true)
            preferenceManager.setFeatureEnableOnUnlock(it, true)
        }
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 camera and microphone are not offered for lock or unlock`() {
        val onLock = configManager.getFeaturesToDisableOnLock()
        val onUnlock = configManager.getFeaturesToEnableOnUnlock()

        assertFalse("camera cannot be switched on Android 11", onLock.contains(PrivacyFeature.CAMERA))
        assertFalse("microphone cannot be switched on Android 11", onLock.contains(PrivacyFeature.MICROPHONE))
        assertFalse("camera cannot be switched on Android 11", onUnlock.contains(PrivacyFeature.CAMERA))
        assertFalse("microphone cannot be switched on Android 11", onUnlock.contains(PrivacyFeature.MICROPHONE))

        // Control: everything else the user asked for is still offered, so the filter is not
        // simply emptying the list.
        assertTrue("WiFi is unaffected", onLock.contains(PrivacyFeature.WIFI))
        assertTrue("NFC is unaffected", onLock.contains(PrivacyFeature.NFC))
        assertTrue("WiFi is unaffected", onUnlock.contains(PrivacyFeature.WIFI))
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 a feature switched off by the user is still left out`() {
        // The other Android 11 test asks for everything, so it cannot tell "the user's choice is
        // honoured" from "every non-sensor feature is returned regardless". This one can.
        preferenceManager.setFeatureDisableOnLock(PrivacyFeature.NFC, false)

        val onLock = configManager.getFeaturesToDisableOnLock()

        assertFalse("NFC must stay out when the user turned it off", onLock.contains(PrivacyFeature.NFC))
        assertTrue("WiFi, which is still asked for, stays in", onLock.contains(PrivacyFeature.WIFI))
    }

    @Test
    @Config(sdk = [31])
    fun `on Android 12 camera and microphone are offered as before`() {
        val onLock = configManager.getFeaturesToDisableOnLock()
        val onUnlock = configManager.getFeaturesToEnableOnUnlock()

        assertTrue("camera works from Android 12", onLock.contains(PrivacyFeature.CAMERA))
        assertTrue("microphone works from Android 12", onLock.contains(PrivacyFeature.MICROPHONE))
        assertTrue("camera works from Android 12", onUnlock.contains(PrivacyFeature.CAMERA))
        assertTrue("microphone works from Android 12", onUnlock.contains(PrivacyFeature.MICROPHONE))
    }

    @Test
    @Config(sdk = [31])
    fun `on Android 12 a camera switched off by the user stays off`() {
        // Without this, every assertion above is equally satisfied by "the user's choice is
        // honoured" and by "camera and mic are always included from Android 12", so a change
        // that dropped the preference check entirely would ship green.
        preferenceManager.setFeatureDisableOnLock(PrivacyFeature.CAMERA, false)
        preferenceManager.setFeatureEnableOnUnlock(PrivacyFeature.MICROPHONE, false)

        assertFalse(
            "camera must stay out when the user turned it off",
            configManager.getFeaturesToDisableOnLock().contains(PrivacyFeature.CAMERA)
        )
        assertFalse(
            "microphone must stay out when the user turned it off",
            configManager.getFeaturesToEnableOnUnlock().contains(PrivacyFeature.MICROPHONE)
        )
        assertTrue(
            "the other half of the same feature is untouched",
            configManager.getFeaturesToEnableOnUnlock().contains(PrivacyFeature.CAMERA)
        )
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 the stored camera and microphone settings are left alone`() {
        // The screen shows these greyed with a note, but what is SAVED must not change: an OS
        // update to Android 12 keeps the app's data, and a setting quietly rewritten here would
        // be the user's choice lost rather than merely unavailable.
        //
        // One of each value on purpose. Both camera/mic defaults are ON (Constants.Defaults), so
        // a version of this that only stored ON could not tell "left alone" from "the key was
        // deleted and the read fell back to the default". The OFF half catches that, and the ON
        // half catches an implementation that blanket-writes OFF.
        preferenceManager.setFeatureDisableOnLock(PrivacyFeature.CAMERA, false)
        preferenceManager.setFeatureEnableOnUnlock(PrivacyFeature.MICROPHONE, true)

        configManager.getFeaturesToDisableOnLock()
        configManager.getFeaturesToEnableOnUnlock()

        assertFalse(
            "a camera setting the user turned OFF must still read OFF, not fall back to the default",
            preferenceManager.getFeatureDisableOnLock(PrivacyFeature.CAMERA)
        )
        assertTrue(
            "a microphone setting the user left ON must not be written OFF",
            preferenceManager.getFeatureEnableOnUnlock(PrivacyFeature.MICROPHONE)
        )
    }

    @Test
    @Config(sdk = [30])
    fun `the availability check itself reports false below Android 12`() {
        assertFalse(DeviceDetector.supportsSensorPrivacyToggle())
    }

    @Test
    @Config(sdk = [31])
    fun `the availability check itself reports true from Android 12`() {
        assertTrue(DeviceDetector.supportsSensorPrivacyToggle())
    }
}
