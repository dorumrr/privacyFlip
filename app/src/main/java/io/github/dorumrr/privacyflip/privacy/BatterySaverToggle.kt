package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

class BatterySaverToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.BATTERY_SAVER
    override val featureName = "Battery Saver"

    override val enableCommands = listOf(
        CommandSet("settings put global low_power 1 && cmd power set-mode 1",
                  description = "Settings + power command method"),
        CommandSet("settings put global low_power 1",
                  description = "Settings database only"),
        CommandSet("cmd power set-mode 1",
                  description = "Power command only")
    )

    override val disableCommands = listOf(
        CommandSet("settings put global low_power 0 && cmd power set-mode 0",
                  description = "Settings + power command method"),
        CommandSet("settings put global low_power 0",
                  description = "Settings database only"),
        CommandSet("cmd power set-mode 0",
                  description = "Power command only")
    )

    override val statusCommands = listOf(
        CommandSet("settings get global low_power", description = "Check battery saver status")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseStandardOutput(output)
    }
}
