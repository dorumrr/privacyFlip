package io.github.dorumrr.privacyflip.privilege

import io.github.dorumrr.privacyflip.data.PrivacyFeature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dhizuku shares Device Owner rights, not shell rights. Four of the nine features have no Device
 * Owner route at all, and telling the user a plain "failed" for something impossible is the same
 * fault as telling them it worked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DhizukuFeaturePolicyTest {

    @Test
    fun `the four features with no Device Owner route are reported unsupported`() {
        listOf(
            PrivacyFeature.WIFI,
            PrivacyFeature.MOBILE_DATA,
            PrivacyFeature.AIRPLANE_MODE,
            PrivacyFeature.BATTERY_SAVER
        ).forEach {
            assertFalse("${it.displayName} has no Device Owner API", DhizukuFeaturePolicy.supports(it))
        }
    }

    @Test
    fun `the five features with a Device Owner route are supported`() {
        listOf(
            PrivacyFeature.LOCATION,
            PrivacyFeature.CAMERA,
            PrivacyFeature.BLUETOOTH,
            PrivacyFeature.MICROPHONE,
            PrivacyFeature.NFC
        ).forEach {
            assertTrue("${it.displayName} has a Device Owner API on API 34", DhizukuFeaturePolicy.supports(it))
        }
    }

    @Test
    fun `an unsupported feature says why, and points somewhere that works`() {
        // This reads as the tail of "Failed to disable X: ...", so it must not repeat the feature
        // name, and it must not read like a transient failure the user could retry away.
        val reason = DhizukuFeaturePolicy.unsupportedReason(PrivacyFeature.BATTERY_SAVER)
        assertTrue("it must name the real cause, was: $reason", reason.contains("DEVICE_POWER"))
        assertTrue("and offer a way out, was: $reason", reason.contains("Root or Shizuku"))
        assertFalse("and never repeat the feature name, was: $reason", reason.contains("Battery Saver"))
    }

    @Test
    fun `a feature whose restriction is too new for this device is not claimed`() {
        // NFC's restriction arrived in API 34. Claiming it on an older phone would mean the app
        // silently did nothing while reporting success.
        assertTrue("API 34 has it", DhizukuFeaturePolicy.supports(PrivacyFeature.NFC))
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 the newer restrictions are refused, and the older ones still work`() {
        assertFalse("NFC's restriction needs API 34", DhizukuFeaturePolicy.supports(PrivacyFeature.NFC))
        assertTrue("Location's setter is API 30", DhizukuFeaturePolicy.supports(PrivacyFeature.LOCATION))
        assertTrue("Bluetooth's restriction is API 28", DhizukuFeaturePolicy.supports(PrivacyFeature.BLUETOOTH))
        val reason = DhizukuFeaturePolicy.unsupportedReason(PrivacyFeature.NFC)
        assertTrue("and it must say which API is missing, was: $reason", reason.contains("API 34"))
    }

    @Test
    @Config(sdk = [26])
    fun `on Android 8 only the oldest routes are claimed`() {
        assertFalse("Location's setter needs API 30", DhizukuFeaturePolicy.supports(PrivacyFeature.LOCATION))
        assertFalse("Bluetooth's restriction needs API 28", DhizukuFeaturePolicy.supports(PrivacyFeature.BLUETOOTH))
        assertTrue("setCameraDisabled is ancient", DhizukuFeaturePolicy.supports(PrivacyFeature.CAMERA))
        assertTrue("so is the microphone restriction", DhizukuFeaturePolicy.supports(PrivacyFeature.MICROPHONE))
    }
}
