package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

class MobileDataToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.MOBILE_DATA
    override val featureName = "Mobile Data"

    override val enableCommands = listOf(
        CommandSet("svc data enable", description = "Service control method"),
        CommandSet("settings put global mobile_data 1", description = "Settings database method"),
        CommandSet("am broadcast -a android.intent.action.ANY_DATA_STATE --ez state true",
                  description = "Broadcast method")
    )

    override val disableCommands = listOf(
        CommandSet("svc data disable", description = "Service control method"),
        CommandSet("settings put global mobile_data 0", description = "Settings database method"),
        CommandSet("am broadcast -a android.intent.action.ANY_DATA_STATE --ez state false",
                  description = "Broadcast method")
    )

    override val statusCommands = listOf(
        CommandSet("settings get global mobile_data", description = "Check mobile data status"),
        CommandSet("dumpsys telephony.registry | grep mDataConnectionState", description = "Dumpsys method")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseStandardOutput(output)
    }
}
