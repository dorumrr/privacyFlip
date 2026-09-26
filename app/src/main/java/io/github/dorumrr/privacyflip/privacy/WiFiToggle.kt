package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

open class WiFiToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.WIFI
    override val featureName = "WiFi"

    // No wifi_on fallback: the Wi-Fi service only stores that key, so writing it switches nothing
    // and reading it back only echoes the write (it also reads 3 while Airplane Mode holds Wi-Fi off).
    override val enableCommands = listOf(
        CommandSet("svc wifi enable", description = "Service control method")
    )

    override val disableCommands = listOf(
        CommandSet("svc wifi disable", description = "Service control method")
    )

    // The key only for a backend that may not dump services (Dhizuku); nothing here writes it.
    override val statusCommands = listOf(
        CommandSet("dumpsys wifi | grep 'Wi-Fi is'", description = "Wi-Fi service state"),
        CommandSet("settings get global wifi_on", description = "Stored Wi-Fi state")
    )

    // The service switches in the background: "disabling" can still show right after svc returns.
    override val readBackAttempts: Int get() = 14

    // A switching state is neither on nor off: unknown, so no caller acts on it and the read-back waits.
    // Stored values: 1 on, 2 on in Airplane Mode, 3 off by it.
    override fun parseStatusOutput(output: String): FeatureState {
        when (Regex("wi-fi is (enabled|disabled)\\b").find(output.lowercase())?.groupValues?.get(1)) {
            "enabled" -> return FeatureState.ENABLED
            "disabled" -> return FeatureState.DISABLED
        }
        return when (output.trim()) {
            "1", "2" -> FeatureState.ENABLED
            "0", "3" -> FeatureState.DISABLED
            else -> FeatureState.UNKNOWN
        }
    }
}
