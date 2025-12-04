package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

class AirplaneModeToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.AIRPLANE_MODE
    override val featureName = "Airplane Mode"

    override val enableCommands = listOf(
        CommandSet("settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
                  description = "Settings + broadcast method"),
        CommandSet("settings put global airplane_mode_on 1",
                  description = "Settings database only")
    )

    override val disableCommands = listOf(
        CommandSet("settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false",
                  description = "Settings + broadcast method"),
        CommandSet("settings put global airplane_mode_on 0",
                  description = "Settings database only")
    )

    override val statusCommands = listOf(
        CommandSet("settings get global airplane_mode_on", description = "Check airplane mode status")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseStandardOutput(output)
    }
}
