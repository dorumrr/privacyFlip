package io.github.dorumrr.privacyflip.privacy

import android.os.Build
import io.github.dorumrr.privacyflip.privilege.CommandResult

/**
 * A device whose settings keys and real states behave as measured on API 26, 34 and 35 emulators:
 * wifi_on / bluetooth_on / mobile_data are only stored, low_power and (from API 29) location_mode
 * switch the real state, the airplane broadcast is refused to the shell user, Battery Saver is
 * refused on a charger, and API 26 has no airplane shell command and no mIsDataEnabled line.
 * Command lists run like PrivilegeExecutor.executeWithFallbacks: the first that exits 0 wins.
 */
class FakeDeviceShell(
    var asRoot: Boolean = false,
    var charging: Boolean = false,
    var svcWifiKilled: Boolean = false,
    var svcBluetoothFails: Boolean = false,
    var svcDataFails: Boolean = false,
    var dumpDenied: Boolean = false,
    var bluetoothReadsBeforeOn: Int = 0,
    var wifiReadsDisabling: Int = 0,
    var wifiReadsBeforeOn: Int = 0,
    var svcWifiNoop: Boolean = false,
    var bluetoothRevertsToOff: Boolean = false,
    var bluetoothReadsBeforeOff: Int = 0,
    var svcBluetoothNoop: Boolean = false
) {
    private var bluetoothOffLeft = 0
    private var wifiStaleLeft = 0
    private var bluetoothReadsLeft = 0
    private var wifiDisablingLeft = 0
    val sdk = Build.VERSION.SDK_INT
    var wifiOn = true
    var bluetoothOn = false
    var airplaneOn = false
    var saverOn = false
    var dataOn = true
    val providers = linkedSetOf("gps")
    val global = mutableMapOf("wifi_on" to "1", "bluetooth_on" to "0", "airplane_mode_on" to "0", "low_power" to "0", "mobile_data" to "1")
    val secure = mutableMapOf<String, String>()
    val ran = mutableListOf<String>()

    fun runList(commands: List<String>): CommandResult {
        var last = CommandResult.failure("no commands")
        for (command in commands) {
            last = runChain(command)
            if (last.success) return last
        }
        return last
    }

    private fun runChain(command: String): CommandResult {
        var last = CommandResult.success()
        for (part in command.split(" && ")) {
            last = run(part.trim())
            if (!last.success) return last
        }
        return last
    }

    private fun ok(vararg lines: String) = CommandResult.success(lines.toList())
    private fun fail(error: String, code: Int = 1) = CommandResult.failure(error, code)

    private fun run(command: String): CommandResult {
        ran += command
        return when {
            command == "[ \"\$(id -u)\" = 0 ]" -> if (asRoot) ok() else fail("not root")

            command.startsWith("svc wifi ") && svcWifiNoop -> ok()
            command.startsWith("svc wifi ") ->
                if (svcWifiKilled) fail("Killed", 137)
                else {
                    wifiOn = command.endsWith("enable")
                    global["wifi_on"] = if (wifiOn) "1" else "0"
                    if (!wifiOn) wifiDisablingLeft = wifiReadsDisabling else wifiStaleLeft = wifiReadsBeforeOn
                    ok()
                }
            command.startsWith("svc bluetooth ") -> when {
                sdk < 27 -> ok("Available commands:", "    help     Show information about the subcommands")
                svcBluetoothNoop -> ok("Success")
                svcBluetoothFails -> fail("bluetooth: no permission")
                else -> {
                    bluetoothOn = command.endsWith("enable")
                    global["bluetooth_on"] = if (bluetoothOn) "1" else "0"
                    if (bluetoothOn) bluetoothReadsLeft = bluetoothReadsBeforeOn else bluetoothOffLeft = bluetoothReadsBeforeOff
                    if (bluetoothOn && bluetoothRevertsToOff) { bluetoothOn = false; global["bluetooth_on"] = "0" }
                    ok("Success")
                }
            }
            command.startsWith("svc data ") ->
                if (svcDataFails) fail("data: no permission") else { dataOn = command.endsWith("enable"); ok() }

            command.startsWith("cmd connectivity airplane-mode ") -> when {
                sdk < 28 -> ok("No shell command implementation.")
                sdk < 30 && !asRoot -> fail("Security exception: requires CONNECTIVITY_INTERNAL", 255)
                else -> { setAirplane(command.endsWith("enable")); ok() }
            }
            command.startsWith("am broadcast -a android.intent.action.AIRPLANE_MODE") ->
                if (asRoot) { setAirplane(global["airplane_mode_on"] == "1"); ok("Broadcast completed: result=0") }
                else fail("Security exception: Permission Denial: not allowed to send broadcast", 255)
            command.startsWith("am broadcast") || command.startsWith("am start") ->
                if (asRoot) ok("Broadcast completed: result=0") else fail("Security exception: Permission Denial", 255)

            command.startsWith("cmd power set-mode ") && sdk < 26 -> ok("No shell command implementation.")
            command.startsWith("cmd power set-mode ") -> {
                val on = command.endsWith("1")
                if (!(on && charging)) { saverOn = on; global["low_power"] = if (on) "1" else "0" }
                ok()
            }

            command.startsWith("settings put global ") -> {
                val (key, value) = command.removePrefix("settings put global ").split(" ", limit = 2)
                global[key] = value
                if (key == "low_power" && !(value == "1" && charging)) saverOn = value == "1"
                ok()
            }
            command.startsWith("settings put secure location_providers_allowed ") -> {
                val value = command.removePrefix("settings put secure location_providers_allowed ")
                if (sdk < 29) {
                    when {
                        value.startsWith("+") -> providers += value.drop(1)
                        value.startsWith("-") -> providers -= value.drop(1)
                    }
                } else secure["location_providers_allowed"] = value
                ok()
            }
            command.startsWith("settings put secure location_mode ") -> {
                val value = command.removePrefix("settings put secure location_mode ")
                secure["location_mode"] = value
                if (sdk >= 29) { providers.clear(); if (value != "0") providers += listOf("gps", "network") }
                ok()
            }
            command.startsWith("settings get global ") -> ok(global[command.removePrefix("settings get global ")] ?: "null")
            command == "settings get secure location_providers_allowed" ->
                ok(if (sdk < 29) providers.joinToString(",") else secure["location_providers_allowed"] ?: "")
            command == "settings get secure location_mode" ->
                ok(if (sdk < 29) secure["location_mode"] ?: "null" else if (providers.isEmpty()) "0" else "3")

            command.startsWith("dumpsys") && dumpDenied -> fail("Permission Denial: can't dump")
            command.startsWith("dumpsys wifi") ->
                if (wifiDisablingLeft > 0) { wifiDisablingLeft--; ok("Wi-Fi is disabling") }
                else if (wifiStaleLeft > 0) { wifiStaleLeft--; ok("Wi-Fi is disabled") }
                else ok(if (wifiOn) "Wi-Fi is enabled" else "Wi-Fi is disabled")
            command.startsWith("dumpsys bluetooth_manager") -> when {
                bluetoothReadsLeft > 1 -> { bluetoothReadsLeft--; ok("  enabled: false", "  state: OFF") }
                bluetoothReadsLeft == 1 -> { bluetoothReadsLeft--; ok("  enabled: false", "  state: TURNING_ON") }
                bluetoothOffLeft > 1 -> { bluetoothOffLeft--; ok("  enabled: true", "  state: ON") }
                bluetoothOffLeft == 1 -> { bluetoothOffLeft--; ok("  enabled: false", "  state: TURNING_OFF") }
                else -> ok("  enabled: $bluetoothOn", "  state: ${if (bluetoothOn) "ON" else "OFF"}")
            }
            command.startsWith("dumpsys power") && command.contains("Battery saver state machine") ->
                if (sdk >= 28) ok("  Enabled=$saverOn") else fail("no match")
            command.startsWith("dumpsys power") && command.contains("mLowPowerModeEnabled") ->
                if (sdk < 28) ok("  mLowPowerModeEnabled=$saverOn") else fail("no match")
            command.startsWith("SUBID=") -> if (sdk >= 31) ok("    mIsDataEnabled=$dataOn") else ok()
            command.startsWith("dumpsys telephony.registry") ->
                if (sdk >= 31) ok("    mIsDataEnabled=$dataOn") else fail("no match")
            command.startsWith("dumpsys location") -> ok()
            else -> fail("unknown command: $command")
        }
    }

    private fun setAirplane(on: Boolean) {
        airplaneOn = on
        global["airplane_mode_on"] = if (on) "1" else "0"
        if (on) wifiOn = false
    }
}
