package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

open class BluetoothToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.BLUETOOTH
    override val featureName = "Bluetooth"

    // No bluetooth_on fallback: the Bluetooth service only stores that key, and it reads 2 while
    // Airplane Mode holds Bluetooth off. Below Android 8.1 svc has no bluetooth command at all.
    override val enableCommands = listOf(
        CommandSet("svc bluetooth enable", description = "Service control method")
    )

    override val disableCommands = listOf(
        CommandSet("svc bluetooth disable", description = "Service control method")
    )

    // The key only for a backend that may not dump services (Dhizuku); nothing here writes it.
    override val statusCommands = listOf(
        CommandSet("dumpsys bluetooth_manager | grep -E '^ *(enabled|state):'", description = "Bluetooth service state"),
        CommandSet("settings get global bluetooth_on", description = "Stored Bluetooth state")
    )

    // The adapter reads OFF, then TURNING_ON, for up to a couple of seconds after svc returns.
    override val readBackAttempts: Int get() = 14

    // A switching state is neither on nor off: unknown, so no caller acts on it and the read-back waits.
    // BLE_ON is classic Bluetooth off; a stored 2 is "on after Airplane Mode".
    override fun parseStatusOutput(output: String): FeatureState {
        val text = output.lowercase()
        Regex("state:\\s*([a-z_]+)").find(text)?.let {
            return when (it.groupValues[1]) {
                "on" -> FeatureState.ENABLED
                "off", "ble_on" -> FeatureState.DISABLED
                else -> FeatureState.UNKNOWN
            }
        }
        Regex("enabled:\\s*(true|false)").find(text)?.let {
            return if (it.groupValues[1] == "true") FeatureState.ENABLED else FeatureState.DISABLED
        }
        return when (output.trim()) {
            "1" -> FeatureState.ENABLED
            "0" -> FeatureState.DISABLED
            else -> FeatureState.UNKNOWN
        }
    }
}
