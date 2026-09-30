package io.github.dorumrr.privacyflip.util

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.github.dorumrr.privacyflip.data.PrivacyFeature
import io.github.dorumrr.privacyflip.root.RootManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** "Only if not connected" must keep Bluetooth on for LE Audio earbuds, not only classic ones. */
@RunWith(RobolectricTestRunner::class)
class BluetoothInUseTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val checker = ConnectionStateChecker(context, RootManager.getInstance(Unit))
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before
    fun bluetoothOnAndAllowed() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        playThrough(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    private fun playThrough(vararg types: Int) =
        shadowOf(audio).setOutputDevices(types.map { AudioDeviceInfoBuilder.newBuilder().setType(it).build() })

    private fun inUse() = runBlocking { checker.isFeatureInUse(PrivacyFeature.BLUETOOTH) }

    @Test
    @Config(sdk = [34])
    fun `earbuds connected only over LE Audio count as connected`() {
        shadowOf(adapter).setProfileConnectionState(BluetoothProfile.LE_AUDIO, BluetoothProfile.STATE_CONNECTED)

        assertTrue(inUse())
    }

    // Android 13 never records LE Audio in the adapter's connection state, so only the sound route shows it.
    @Test
    @Config(sdk = [33])
    fun `an LE Audio headset carrying the sound counts as connected`() {
        playThrough(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLE_HEADSET)

        assertTrue(inUse())
    }

    @Test
    @Config(sdk = [33])
    fun `an LE Audio speaker carrying the sound counts as connected`() {
        playThrough(AudioDeviceInfo.TYPE_BLE_SPEAKER)

        assertTrue(inUse())
    }

    @Test
    @Config(sdk = [33])
    fun `an LE Audio broadcast from the phone counts as in use`() {
        playThrough(AudioDeviceInfo.TYPE_BLE_BROADCAST)

        assertTrue(inUse())
    }

    @Test
    @Config(sdk = [34])
    fun `classic headphones still count as connected`() {
        shadowOf(adapter).setProfileConnectionState(BluetoothProfile.A2DP, BluetoothProfile.STATE_CONNECTED)

        assertTrue(inUse())
    }

    @Test
    @Config(sdk = [34])
    fun `nothing connected and sound on the phone speaker is not in use`() {
        assertFalse(inUse())
    }

    @Test
    @Config(sdk = [34])
    fun `a connection type the check does not count is not in use`() {
        shadowOf(adapter).setProfileConnectionState(BluetoothProfile.HID_DEVICE, BluetoothProfile.STATE_CONNECTED)

        assertFalse(inUse())
    }

    @Test
    @Config(sdk = [34])
    fun `without the Bluetooth permission nothing counts as connected`() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setProfileConnectionState(BluetoothProfile.LE_AUDIO, BluetoothProfile.STATE_CONNECTED)
        playThrough(AudioDeviceInfo.TYPE_BLE_HEADSET)

        assertFalse(inUse())
    }

    @Test
    @Config(sdk = [31])
    fun `on Android 12 an LE Audio headset carrying the sound counts as connected`() {
        playThrough(AudioDeviceInfo.TYPE_BLE_HEADSET)

        assertTrue(inUse())
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12 nothing is counted as LE Audio`() {
        shadowOf(adapter).setProfileConnectionState(BluetoothProfile.LE_AUDIO, BluetoothProfile.STATE_CONNECTED)
        playThrough(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_BROADCAST)

        assertFalse(inUse())
    }

    @Test
    @Config(sdk = [34])
    fun `an LE Audio output left over while Bluetooth is off is not in use`() {
        shadowOf(adapter).setEnabled(false)
        playThrough(AudioDeviceInfo.TYPE_BLE_HEADSET)

        assertFalse(inUse())
    }
}
