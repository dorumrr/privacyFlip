package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager

class NFCToggle(
    rootManager: RootManager,
    private val context: Context
) : BasePrivacyToggle(rootManager) {

    override val feature = PrivacyFeature.NFC
    override val featureName = "NFC"

    override val enableCommands = listOf(
        CommandSet("svc nfc enable")
    )

    override val disableCommands = listOf(
        CommandSet("svc nfc disable")
    )

    override val statusCommands = listOf(
        CommandSet("dumpsys nfc | grep 'mState='")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        return when {
            output.contains("mState=on", ignoreCase = true) -> FeatureState.ENABLED
            output.contains("mState=off", ignoreCase = true) -> FeatureState.DISABLED
            output.isEmpty() -> FeatureState.UNAVAILABLE
            else -> FeatureState.UNKNOWN
        }
    }
}

