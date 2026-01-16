package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

class NFCToggle(
    rootManager: RootManager,
    private val context: Context
) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.NFC
    override val featureName = "NFC"

    override val enableCommands = listOf(
        CommandSet("svc nfc enable", description = "Service control method (primary)"),
        CommandSet("settings put global nfc_on 1", description = "Settings database method"),
        CommandSet("cmd nfc enable", description = "Modern cmd method (Android 8+)")
    )

    override val disableCommands = listOf(
        CommandSet("svc nfc disable", description = "Service control method (primary)"),
        CommandSet("settings put global nfc_on 0", description = "Settings database method"),
        CommandSet("cmd nfc disable", description = "Modern cmd method (Android 8+)")
    )

    override val statusCommands = listOf(
        CommandSet("dumpsys nfc | grep 'mState='", description = "Primary status check"),
        CommandSet("settings get global nfc_on", description = "Settings database check")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        Log.d(TAG, "🔍 Parsing NFC status output: '$output'")

        val state = when {
            output.contains("mState=on", ignoreCase = true) -> FeatureState.ENABLED
            output.contains("mState=off", ignoreCase = true) -> FeatureState.DISABLED
            output.contains("1") -> FeatureState.ENABLED
            output.contains("0") -> FeatureState.DISABLED
            output.isEmpty() -> FeatureState.UNAVAILABLE
            else -> FeatureState.UNKNOWN
        }

        Log.d(TAG, "🔍 Parsed NFC state: $state")
        return state
    }
}

