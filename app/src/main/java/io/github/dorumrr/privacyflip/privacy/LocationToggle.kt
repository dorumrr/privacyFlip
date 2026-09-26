package io.github.dorumrr.privacyflip.privacy

import android.os.Build
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

open class LocationToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.LOCATION
    override val featureName = "Location Services"

    override val enableCommands = getEnableCommandsForApi()
    override val disableCommands = getDisableCommandsForApi()
    override val statusCommands = getStatusCommandsForApi()
    
    // Before Android 10 the location service obeys only location_providers_allowed, which takes only
    // +provider / -provider: location_mode, '' and a plain list all exit 0 and change nothing.
    private fun getEnableCommandsForApi(): List<CommandSet> =
        if (Build.VERSION.SDK_INT >= 29) {
            listOf(
                CommandSet("settings put secure location_mode 3", description = "High accuracy mode"),
                CommandSet("settings put secure location_providers_allowed +gps,+network",
                          description = "Enable GPS and network providers")
            )
        } else {
            listOf(
                CommandSet("settings put secure location_providers_allowed +gps && settings put secure location_providers_allowed +network",
                          description = "Enable GPS and network providers")
            )
        }

    private fun getDisableCommandsForApi(): List<CommandSet> =
        if (Build.VERSION.SDK_INT >= 29) {
            listOf(
                CommandSet("settings put secure location_mode 0", description = "Disable location"),
                CommandSet("settings put secure location_providers_allowed ''",
                          description = "Clear location providers")
            )
        } else {
            listOf(
                CommandSet("settings put secure location_providers_allowed -gps && settings put secure location_providers_allowed -network",
                          description = "Disable GPS and network providers")
            )
        }

    private fun getStatusCommandsForApi(): List<CommandSet> =
        if (Build.VERSION.SDK_INT >= 29) {
            listOf(
                CommandSet("settings get secure location_mode", description = "Check location mode"),
                CommandSet("settings get secure location_providers_allowed", description = "Check providers"),
                CommandSet("dumpsys location | grep 'Location'", description = "Dumpsys method")
            )
        } else {
            listOf(
                CommandSet("settings get secure location_providers_allowed", description = "Check providers")
            )
        }

    override fun parseStatusOutput(output: String): FeatureState {
        return StatusParsingUtils.parseLocationOutput(output)
    }
}
