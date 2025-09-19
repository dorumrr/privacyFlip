package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

class WiFiToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.WIFI
    override val featureName = "WiFi"

    override val enableCommands = listOf(
        CommandSet("svc wifi enable", description = "Service control method"),
        CommandSet("settings put global wifi_on 1", description = "Settings database method"),
        CommandSet("am broadcast -a android.net.wifi.WIFI_STATE_CHANGED --ei wifi_state 3",
                  description = "Broadcast method")
    )

    override val disableCommands = listOf(
        CommandSet("svc wifi disable", description = "Service control method"),
        CommandSet("settings put global wifi_on 0", description = "Settings database method"),
        CommandSet("am broadcast -a android.net.wifi.WIFI_STATE_CHANGED --ei wifi_state 1",
                  description = "Broadcast method")
    )

    override val statusCommands = listOf(
        CommandSet("settings get global wifi_on", description = "Check WiFi status"),
        CommandSet("dumpsys wifi | grep 'Wi-Fi is'", description = "Dumpsys method")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseStandardOutput(output)
    }
}
