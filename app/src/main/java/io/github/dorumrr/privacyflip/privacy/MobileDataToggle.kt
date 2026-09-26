package io.github.dorumrr.privacyflip.privacy

import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.StatusParsingUtils

open class MobileDataToggle(rootManager: RootManager) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.MOBILE_DATA
    override val featureName = "Mobile Data"

    // No mobile_data fallback: telephony only stores that key (per SIM on dual-SIM phones), and the
    // ANY_DATA_STATE broadcast is a state report, not a command.
    override val enableCommands = listOf(
        CommandSet("svc data enable", description = "Service control method")
    )

    override val disableCommands = listOf(
        CommandSet("svc data disable", description = "Service control method")
    )

    // A dual-SIM dump lists one mIsDataEnabled per SIM, so the first reads the data-carrying SIM's.
    // Its awk exits 0 even with no match (no such line before Android 12), so then the state reads unknown.
    override val statusCommands = listOf(
        CommandSet(
            "SUBID=\$(dumpsys telephony.registry | grep -m1 'mActiveDataSubId=' | sed 's/.*=//'); " +
                "PHONEID=\$(dumpsys telephony.registry | grep -m1 \"phoneId=[0-9]* subId=\$SUBID\" | sed -E 's/.*phoneId=([0-9]+).*/\\1/'); " +
                "dumpsys telephony.registry | awk -v p=\"Phone Id=\$PHONEID\" 'index(\$0,p){f=1} f&&/mIsDataEnabled=/{print;exit}'",
            description = "Telephony data-enabled state for the active data SIM"
        ),
        CommandSet("dumpsys telephony.registry | grep -m1 mIsDataEnabled", description = "Telephony data-enabled state (first SIM listed - fallback, may be the wrong SIM on dual-SIM)"),
        CommandSet("settings get global mobile_data", description = "Settings database method (fallback)")
    )

    // Public (widened from the base class's protected, which Kotlin allows) so the regression
    // test for this exact parsing logic (MobileDataToggleTest.kt) can call it directly instead
    // of needing a subclass or reflection just to reach it.
    public override fun parseStatusOutput(output: String): FeatureState {
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
