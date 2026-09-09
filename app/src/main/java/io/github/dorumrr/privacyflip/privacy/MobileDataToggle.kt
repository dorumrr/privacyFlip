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

    // Checked first: the real per-SIM enabled flag from the telephony service itself.
    // "settings get global mobile_data" is a legacy, single-SIM-era flag. On some
    // phones (dual-SIM ones in particular) it can report "on" even while data is
    // genuinely off, which made the app wrongly skip re-enabling data on unlock.
    // grep with no match exits non-zero, so this correctly falls through to the
    // settings flag below on any device where the telephony dump doesn't have it.
    override val statusCommands = listOf(
        CommandSet("dumpsys telephony.registry | grep -m1 mIsDataEnabled", description = "Telephony data-enabled state"),
        CommandSet("settings get global mobile_data", description = "Settings database method (fallback)")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        // The telephony line looks like "mIsDataEnabled=true". Its field NAME
        // contains the word "enabled" regardless of the actual value, so the
        // generic parser (which just looks for the word "enabled" anywhere)
        // would misread "mIsDataEnabled=false" as enabled. Check the value
        // after the '=' explicitly instead.
        val telephonyValue = Regex("isdataenabled=(true|false)")
            .find(output.lowercase())
            ?.groupValues?.get(1)
        if (telephonyValue != null) {
            return if (telephonyValue == "true") FeatureState.ENABLED else FeatureState.DISABLED
        }
        return StatusParsingUtils.parseStandardOutput(output)
    }
}
