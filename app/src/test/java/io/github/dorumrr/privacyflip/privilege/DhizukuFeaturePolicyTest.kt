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
@Config(sdk = [35])
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
            assertTrue("${it.displayName} has a Device Owner API on API 35", DhizukuFeaturePolicy.supports(it))
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
    @Config(sdk = [34])
    fun `NFC is refused on Android 14, whose UserManager has no such restriction`() {
        // DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO is absent from android-34.jar and present in
        // android-35.jar. Claiming it on Android 14 would write a restriction key the platform
        // does not know, and then read it back as if NFC were off.
        assertFalse("Android 14 does not have it", DhizukuFeaturePolicy.supports(PrivacyFeature.NFC))
        val reason = DhizukuFeaturePolicy.unsupportedReason(PrivacyFeature.NFC)
        assertTrue("and it must name the version needed, was: $reason", reason.contains("API 35"))
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 the newer restrictions are refused, and the older ones still work`() {
        assertFalse("NFC's restriction needs API 35", DhizukuFeaturePolicy.supports(PrivacyFeature.NFC))
        assertTrue("Location's setter is API 30", DhizukuFeaturePolicy.supports(PrivacyFeature.LOCATION))
        assertTrue("Bluetooth's restriction is API 28", DhizukuFeaturePolicy.supports(PrivacyFeature.BLUETOOTH))
        val reason = DhizukuFeaturePolicy.unsupportedReason(PrivacyFeature.NFC)
        assertTrue("and it must say which API is missing, was: $reason", reason.contains("API 35"))
    }

    @Test
    fun `the stale-block sweep runs only while the screen is unlocked`() {
        // A restriction and a camera policy both outlive the process and cannot be lifted from
        // Settings, so they must be swept. But sweeping during a real lock would undo the block
        // the user asked for, every time anything re-ran detection.
        assertTrue("an unlocked phone with no lock in flight may be swept",
            DhizukuFeaturePolicy.shouldSweep(screenLocked = false, lockIsInFlight = false))
        assertFalse("a locked phone's blocks are doing their job",
            DhizukuFeaturePolicy.shouldSweep(screenLocked = true, lockIsInFlight = false))
        // The accessibility producer starts a lock while the screen is STILL ON, so screen state
        // alone would sweep away the blocks that same lock is about to rely on.
        assertFalse("a lock in flight wins even with the screen still on",
            DhizukuFeaturePolicy.shouldSweep(screenLocked = false, lockIsInFlight = true))
    }

    @Test
    @Config(sdk = [26])
    fun `on Android 8 only the oldest routes are claimed`() {
        assertFalse("Location's setter needs API 30", DhizukuFeaturePolicy.supports(PrivacyFeature.LOCATION))
        assertFalse("Bluetooth's restriction needs API 28", DhizukuFeaturePolicy.supports(PrivacyFeature.BLUETOOTH))
        // The app drops both below Android 12, so claiming them here would advertise something
        // no configuration can reach. Every doc states the same floor.
        assertFalse("camera is dropped below Android 12", DhizukuFeaturePolicy.supports(PrivacyFeature.CAMERA))
        assertFalse("and so is the microphone", DhizukuFeaturePolicy.supports(PrivacyFeature.MICROPHONE))
    }
}
