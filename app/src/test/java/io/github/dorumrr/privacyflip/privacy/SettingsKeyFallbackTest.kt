package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.FeatureState
import io.github.dorumrr.privacyflip.privilege.CommandResult
import io.github.dorumrr.privacyflip.root.RootManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each toggle is driven through its real enable/disable and read-back against a device that
 * behaves as the emulators did. A switch that did not happen must never come back as done.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsKeyFallbackTest {

    private val root = RootManager.getInstance(Unit)

    private class Wifi(val d: FakeDeviceShell) : WiFiToggle(RootManager.getInstance(Unit)) {
        fun parse(output: String) = parseStatusOutput(output)
        override suspend fun runCommands(commands: List<String>): CommandResult = d.runList(commands)
        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
        override suspend fun readFeatureState(): FeatureState? = null
    }

    private class Bluetooth(val d: FakeDeviceShell) : BluetoothToggle(RootManager.getInstance(Unit)) {
        fun parse(output: String) = parseStatusOutput(output)
        override suspend fun runCommands(commands: List<String>): CommandResult = d.runList(commands)
        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
        override suspend fun readFeatureState(): FeatureState? = null
    }

    private class Airplane(val d: FakeDeviceShell) : AirplaneModeToggle(RootManager.getInstance(Unit)) {
        override suspend fun runCommands(commands: List<String>): CommandResult = d.runList(commands)
        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
        override suspend fun readFeatureState(): FeatureState? = null
    }

    private class Saver(val d: FakeDeviceShell) : BatterySaverToggle(RootManager.getInstance(Unit)) {
        override suspend fun runCommands(commands: List<String>): CommandResult = d.runList(commands)
        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
        override suspend fun readFeatureState(): FeatureState? = null
    }

    private class Location(val d: FakeDeviceShell) : LocationToggle(RootManager.getInstance(Unit)) {
        override suspend fun runCommands(commands: List<String>): CommandResult = d.runList(commands)
        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
        override suspend fun readFeatureState(): FeatureState? = null
    }

    private class Data(val d: FakeDeviceShell) : MobileDataToggle(RootManager.getInstance(Unit)) {
        override suspend fun runCommands(commands: List<String>): CommandResult = d.runList(commands)
        override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
        override suspend fun readFeatureState(): FeatureState? = null
    }

    @Test
    @Config(sdk = [34])
    fun `airplane mode the shell user cannot send is not reported as on, and leaves no stale key`() = runBlocking {
        val d = FakeDeviceShell(asRoot = false)
        d.global["airplane_mode_on"] = "0"
        // Android 11+ grants the shell the airplane command, so take it away to reach the fallback.
        val toggle = object : AirplaneModeToggle(root) {
            override suspend fun runCommands(commands: List<String>): CommandResult =
                d.runList(commands.filterNot { it.startsWith("cmd connectivity") })
            override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
            override suspend fun readFeatureState(): FeatureState? = null
        }

        val result = toggle.enable()

        assertFalse("nothing switched, was: ${result.message}", result.success)
        assertFalse(d.airplaneOn)
        assertEquals("the key must not claim airplane mode either", "0", d.global["airplane_mode_on"])
    }

    @Test
    @Config(sdk = [34])
    fun `airplane mode switches for the shell user through the connectivity command`() = runBlocking {
        val d = FakeDeviceShell(asRoot = false)

        val on = Airplane(d).enable()
        assertTrue("was: ${on.message}", on.success)
        assertTrue(d.airplaneOn)

        val off = Airplane(d).disable()
        assertTrue("was: ${off.message}", off.success)
        assertFalse(d.airplaneOn)
    }

    @Test
    @Config(sdk = [26])
    fun `airplane mode on Android 8 still switches as root, where the connectivity command does nothing`() = runBlocking {
        val d = FakeDeviceShell(asRoot = true)

        val result = Airplane(d).enable()

        assertTrue("was: ${result.message}", result.success)
        assertTrue(d.airplaneOn)
    }

    @Test
    @Config(sdk = [26])
    fun `airplane mode on Android 8 as the shell user is reported as failed`() = runBlocking {
        val d = FakeDeviceShell(asRoot = false)

        val result = Airplane(d).enable()

        assertFalse("was: ${result.message}", result.success)
        assertFalse(d.airplaneOn)
        assertEquals("0", d.global["airplane_mode_on"])
    }

    @Test
    @Config(sdk = [34])
    fun `battery saver refused on a charger is not reported as on`() = runBlocking {
        val d = FakeDeviceShell(charging = true)

        val result = Saver(d).enable()

        assertFalse("Android refused it, was: ${result.message}", result.success)
        assertFalse(d.saverOn)
        assertNotEquals("a refused enable must not leave the key armed for later", "1", d.global["low_power"])
    }

    @Test
    @Config(sdk = [34])
    fun `battery saver switches when unplugged and reads back from the power service`() = runBlocking {
        val d = FakeDeviceShell()

        assertTrue(Saver(d).enable().success)
        assertTrue(d.saverOn)
        d.global["low_power"] = "0"
        assertEquals("the state comes from the power service, not the key", FeatureState.ENABLED, Saver(d).getCurrentState())
        assertTrue(Saver(d).disable().success)
        d.global["low_power"] = "1"
        assertEquals(FeatureState.DISABLED, Saver(d).getCurrentState())
    }

    @Test
    @Config(sdk = [25])
    fun `battery saver on Android 7 switches through the key, where the power command does nothing`() = runBlocking {
        val d = FakeDeviceShell()

        assertTrue(Saver(d).enable().success)
        assertTrue(d.saverOn)
        assertTrue(Saver(d).disable().success)
        assertFalse(d.saverOn)
    }

    @Test
    @Config(sdk = [26])
    fun `battery saver state on Android 8 comes from the power service`() = runBlocking {
        val d = FakeDeviceShell()

        assertTrue(Saver(d).enable().success)
        d.global["low_power"] = "0"
        assertEquals(FeatureState.ENABLED, Saver(d).getCurrentState())
        assertTrue(Saver(d).disable().success)
        assertEquals(FeatureState.DISABLED, Saver(d).getCurrentState())
    }

    @Test
    @Config(sdk = [26])
    fun `wifi the shell user cannot switch is not reported as off`() = runBlocking {
        val d = FakeDeviceShell(svcWifiKilled = true)

        val result = Wifi(d).disable()

        assertFalse("svc was killed and nothing else can switch it, was: ${result.message}", result.success)
        assertTrue(d.wifiOn)
        assertEquals("wifi_on is read at boot, so a stale 0 would start the phone with Wi-Fi off", "1", d.global["wifi_on"])
    }

    @Test
    @Config(sdk = [34])
    fun `wifi state comes from the wifi service, not a stale key`() = runBlocking {
        val d = FakeDeviceShell()
        d.global["wifi_on"] = "3"

        assertEquals(FeatureState.ENABLED, Wifi(d).getCurrentState())
        d.wifiOn = false
        d.global["wifi_on"] = "1"
        assertEquals(FeatureState.DISABLED, Wifi(d).getCurrentState())
    }

    @Test
    @Config(sdk = [34])
    fun `bluetooth off under airplane mode reads as off`() = runBlocking {
        val d = FakeDeviceShell()
        d.bluetoothOn = false
        d.global["bluetooth_on"] = "2"

        assertEquals(FeatureState.DISABLED, Bluetooth(d).getCurrentState())
        d.bluetoothOn = true
        assertEquals(FeatureState.ENABLED, Bluetooth(d).getCurrentState())
    }

    @Test
    @Config(sdk = [34])
    fun `bluetooth that takes a moment to turn on is confirmed, not reported as failed`() = runBlocking {
        // As measured on API 34: OFF right after svc returns, then TURNING_ON, then ON.
        val d = FakeDeviceShell(bluetoothReadsBeforeOn = 6)

        val result = Bluetooth(d).enable()

        assertTrue("it did turn on, was: ${result.message}", result.success)
        assertEquals("Bluetooth enabled", result.message)
    }

    @Test
    @Config(sdk = [34])
    fun `wifi that takes a moment to turn on is confirmed`() = runBlocking {
        // As measured on API 35: "disabled" right after svc returns, then "enabled".
        val d = FakeDeviceShell(wifiReadsBeforeOn = 4).apply { wifiOn = false; global["wifi_on"] = "0" }

        val result = Wifi(d).enable()

        assertEquals("WiFi enabled", result.message)
    }

    @Test
    @Config(sdk = [34])
    fun `a switching state reads as unknown, so no caller acts on it`() {
        val bt = Bluetooth(FakeDeviceShell())
        assertEquals(FeatureState.UNKNOWN, bt.parse("  enabled: false   state: turning_on"))
        assertEquals(FeatureState.UNKNOWN, bt.parse("  enabled: true   state: turning_off"))
        assertEquals("the state line wins over enabled:", FeatureState.UNKNOWN, bt.parse("  enabled: false   state: turning_off"))
        assertEquals(FeatureState.UNKNOWN, bt.parse("  enabled: false   state: ble_turning_off"))
        assertEquals("classic Bluetooth is off", FeatureState.DISABLED, bt.parse("  enabled: false   state: ble_on"))
        assertEquals(FeatureState.ENABLED, bt.parse("  enabled: true   state: on"))
        assertEquals(FeatureState.ENABLED, bt.parse("1"))
        assertEquals(FeatureState.UNKNOWN, bt.parse("2"))

        val wifi = Wifi(FakeDeviceShell())
        assertEquals(FeatureState.UNKNOWN, wifi.parse("wi-fi is enabling"))
        assertEquals(FeatureState.UNKNOWN, wifi.parse("wi-fi is disabling"))
        assertEquals(FeatureState.UNKNOWN, wifi.parse("wi-fi is unknown state"))
        assertEquals("3 is off, held off by Airplane Mode", FeatureState.DISABLED, wifi.parse("3"))
        assertEquals(FeatureState.ENABLED, wifi.parse("2"))
    }

    @Test
    @Config(sdk = [34])
    fun `wifi still disabling is waited out and then confirmed`() = runBlocking {
        val d = FakeDeviceShell(wifiReadsDisabling = 2)

        val result = Wifi(d).disable()

        assertTrue("was: ${result.message}", result.success)
        assertEquals("a confirmed switch, not an unreadable one", "WiFi disabled", result.message)
    }

    @Test
    @Config(sdk = [34])
    fun `a switch that turns back or never happens fails after the wait`() = runBlocking {
        val reverts = FakeDeviceShell(bluetoothReadsBeforeOn = 3, bluetoothRevertsToOff = true)
        val bt = Bluetooth(reverts).enable()
        assertFalse("TURNING_ON then OFF is no enable, was: ${bt.message}", bt.success)

        val noop = FakeDeviceShell(svcWifiNoop = true)
        val wifi = Wifi(noop).disable()
        assertFalse("svc exited 0 and nothing changed, was: ${wifi.message}", wifi.success)
        assertEquals("the wait gives up after 14 reads, not sooner or later", 14, noop.ran.count { it.startsWith("dumpsys wifi") })
        assertEquals("1", noop.global["wifi_on"])

        val noopOn = FakeDeviceShell(svcWifiNoop = true).apply { wifiOn = false; global["wifi_on"] = "0" }
        assertFalse(Wifi(noopOn).enable().success)
        assertFalse(noopOn.wifiOn)
        assertEquals("0", noopOn.global["wifi_on"])

        val btNoop = FakeDeviceShell(svcBluetoothNoop = true).apply { bluetoothOn = true; global["bluetooth_on"] = "1" }
        assertFalse(Bluetooth(btNoop).disable().success)
        assertTrue(btNoop.bluetoothOn)
        assertEquals(14, btNoop.ran.count { it.startsWith("dumpsys bluetooth_manager") })
    }

    @Test
    @Config(sdk = [34])
    fun `bluetooth that takes a moment to turn off is confirmed`() = runBlocking {
        val d = FakeDeviceShell(bluetoothReadsBeforeOff = 3).apply { bluetoothOn = true; global["bluetooth_on"] = "1" }

        val result = Bluetooth(d).disable()

        assertEquals("Bluetooth disabled", result.message)
        assertFalse(d.bluetoothOn)
    }

    @Test
    @Config(sdk = [34])
    fun `a backend that may not dump services still reads the stored state`() = runBlocking {
        // Dhizuku runs shell commands as an ordinary app, which may not run dumpsys.
        val d = FakeDeviceShell(dumpDenied = true)
        d.global["wifi_on"] = "1"
        d.global["bluetooth_on"] = "0"

        assertEquals(FeatureState.ENABLED, Wifi(d).getCurrentState())
        assertEquals(FeatureState.DISABLED, Bluetooth(d).getCurrentState())
        d.global["bluetooth_on"] = "2"
        assertEquals("2 is off under Airplane Mode, not on", FeatureState.UNKNOWN, Bluetooth(d).getCurrentState())

        assertTrue("an enable it cannot read back must still be claimable", Saver(d).enable().success)
        assertEquals("so its state reads from the key", FeatureState.ENABLED, Saver(d).getCurrentState())
    }

    @Test
    @Config(sdk = [34])
    fun `bluetooth the shell cannot switch is not reported as on`() = runBlocking {
        val d = FakeDeviceShell(svcBluetoothFails = true)

        val result = Bluetooth(d).enable()

        assertFalse("was: ${result.message}", result.success)
        assertFalse(d.bluetoothOn)
        assertFalse("no dialog may be put in front of the user", d.ran.any { it.startsWith("am start") })
        assertEquals("bluetooth_on is read at boot and on airplane changes, so it must not be left at 1", "0", d.global["bluetooth_on"])
    }

    @Test
    @Config(sdk = [26])
    fun `location on Android 8 is really switched and read back from the providers`() = runBlocking {
        val d = FakeDeviceShell()

        val off = Location(d).disable()
        assertTrue("was: ${off.message}", off.success)
        assertTrue("providers must really be gone, were: ${d.providers}", d.providers.isEmpty())
        d.secure["location_mode"] = "3"
        assertEquals("a stale location_mode must not answer", FeatureState.DISABLED, Location(d).getCurrentState())

        val on = Location(d).enable()
        assertTrue("was: ${on.message}", on.success)
        assertTrue(d.providers.contains("gps"))
        d.secure["location_mode"] = "0"
        assertEquals(FeatureState.ENABLED, Location(d).getCurrentState())
    }

    @Test
    @Config(sdk = [28])
    fun `location on Android 9 goes through the providers, which is all its location service watches`() = runBlocking {
        val d = FakeDeviceShell()
        d.secure["location_mode"] = "3"

        val off = Location(d).disable()
        assertTrue("was: ${off.message}", off.success)
        assertTrue("providers must really be gone, were: ${d.providers}", d.providers.isEmpty())
        assertEquals(FeatureState.DISABLED, Location(d).getCurrentState())
    }

    @Test
    @Config(sdk = [34])
    fun `location on Android 10 and later still switches through location_mode`() = runBlocking {
        val d = FakeDeviceShell()

        assertTrue(Location(d).disable().success)
        assertTrue(d.providers.isEmpty())
        assertTrue(Location(d).enable().success)
        assertTrue(d.providers.isNotEmpty())
    }

    @Test
    @Config(sdk = [26])
    fun `bluetooth on Android 8, which svc cannot switch, is reported as failed`() = runBlocking {
        val d = FakeDeviceShell()

        val result = Bluetooth(d).enable()

        assertFalse("svc printed its help and exited 0, was: ${result.message}", result.success)
        assertFalse(d.bluetoothOn)

        d.bluetoothOn = true
        val off = Bluetooth(d).disable()
        assertFalse("the disable is no different, was: ${off.message}", off.success)
        assertTrue(d.bluetoothOn)
    }

    @Test
    @Config(sdk = [26])
    fun `airplane mode on Android 8 turns off as root and fails as the shell user`() = runBlocking {
        val root = FakeDeviceShell(asRoot = true).apply { airplaneOn = true; global["airplane_mode_on"] = "1" }
        assertTrue(Airplane(root).disable().success)
        assertFalse(root.airplaneOn)

        val shell = FakeDeviceShell(asRoot = false).apply { airplaneOn = true; global["airplane_mode_on"] = "1" }
        assertFalse(Airplane(shell).disable().success)
        assertTrue(shell.airplaneOn)
    }

    @Test
    @Config(sdk = [28])
    fun `airplane mode on Android 9 as the shell user is reported as failed, as root it works`() = runBlocking {
        val shell = FakeDeviceShell(asRoot = false)
        assertFalse(Airplane(shell).enable().success)
        assertFalse(shell.airplaneOn)
        assertEquals("0", shell.global["airplane_mode_on"])

        val root = FakeDeviceShell(asRoot = true)
        assertTrue(Airplane(root).enable().success)
        assertTrue(root.airplaneOn)
    }

    @Test
    @Config(sdk = [34])
    fun `mobile data that switches is confirmed from the telephony line`() = runBlocking {
        val d = FakeDeviceShell()

        assertEquals("Mobile Data disabled", Data(d).disable().message)
        assertFalse(d.dataOn)
        assertEquals(FeatureState.DISABLED, Data(d).getCurrentState())
        assertEquals("Mobile Data enabled", Data(d).enable().message)
    }

    @Test
    @Config(sdk = [34])
    fun `every refused switch fails in both directions and leaves no key behind`() = runBlocking {
        val wifi = FakeDeviceShell(svcWifiKilled = true).apply { wifiOn = false; global["wifi_on"] = "0" }
        assertFalse(Wifi(wifi).enable().success)
        assertEquals("0", wifi.global["wifi_on"])

        val bt = FakeDeviceShell(svcBluetoothFails = true).apply { bluetoothOn = true; global["bluetooth_on"] = "1" }
        assertFalse(Bluetooth(bt).disable().success)
        assertEquals("1", bt.global["bluetooth_on"])

        val data = FakeDeviceShell(svcDataFails = true).apply { dataOn = false; global["mobile_data"] = "0" }
        assertFalse(Data(data).enable().success)
        assertEquals("0", data.global["mobile_data"])

        val air = FakeDeviceShell(asRoot = false).apply { airplaneOn = true; global["airplane_mode_on"] = "1" }
        val toggle = object : AirplaneModeToggle(root) {
            override suspend fun runCommands(commands: List<String>): CommandResult =
                air.runList(commands.filterNot { it.startsWith("cmd connectivity") })
            override suspend fun runFeatureAction(enable: Boolean): CommandResult? = null
            override suspend fun readFeatureState(): FeatureState? = null
        }
        assertFalse(toggle.disable().success)
        assertTrue(air.airplaneOn)
        assertEquals("1", air.global["airplane_mode_on"])
    }

    @Test
    @Config(sdk = [26])
    fun `mobile data a failed svc is not reported as done`() = runBlocking {
        val d = FakeDeviceShell(svcDataFails = true)

        val result = Data(d).disable()

        assertFalse("nothing switched it, was: ${result.message}", result.success)
        assertTrue(d.dataOn)
        assertEquals("telephony re-reads mobile_data later, so it must not be left at 0", "1", d.global["mobile_data"])
    }
}
