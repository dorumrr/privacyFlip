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

    // Checked first: the real per-SIM enabled flag from the telephony service itself,
    // for the specific SIM that's actually carrying data. A dual-SIM phone's dumpsys
    // output lists one "mIsDataEnabled" line per SIM (per "Phone Id="), and a plain
    // "grab the first one" reads whichever SIM happens to be listed first, not
    // necessarily the one the phone is actually using for data - confirmed on a real
    // dual-SIM phone during this project's own post-release audit. This command reads
    // "mActiveDataSubId" (which SIM is actually carrying data), matches it to its
    // "Phone Id=" block via a "phoneId=... subId=..." line dumpsys also logs, then
    // reads that specific block's "mIsDataEnabled" - not just whichever comes first.
    // Falls back to the old first-match version, then the legacy settings flag, if any
    // step above finds nothing (a single-SIM phone, or a dump shape this doesn't expect).
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
    // test for this exact parsing logic (#J1, MobileDataToggleTest.kt) can call it directly
    // instead of needing a subclass or reflection just to reach it.
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
