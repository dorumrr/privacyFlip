package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.delay

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

    /**
     * Re-checks NFC after disabling it, and optionally retries.
     *
     * A payment or wallet framework can silently turn NFC back on moments after this app turns
     * it off. This was first reported on Samsung, but the check itself is not Samsung-specific:
     * it only asks whether NFC came back on, never why, so every device gets it.
     */
    override suspend fun disable(): PrivacyResult {
        Log.d(TAG, "📍 Starting NFC disable sequence...")

        val initialResult = super.disable()

        // Give whatever might re-enable NFC a moment to do so before re-reading the state.
        delay(500)

        val actualState = getCurrentState()
        val wasOverridden = (initialResult.success && actualState == FeatureState.ENABLED)

        if (!wasOverridden) {
            Log.d(TAG, "✅ NFC successfully disabled (it stayed off)")
            return initialResult
        }

        Log.w(TAG, "⚠️ NFC reports enabled again right after being disabled")

        val preferenceManager = PreferenceManager.getInstance(context)
        val autoRetryEnabled = preferenceManager.samsungNfcAutoRetry

        if (!autoRetryEnabled) {
            Log.i(TAG, "Auto-retry disabled by user preference - reporting what was observed")
            return PrivacyResult(
                feature = feature,
                success = false,
                message = "NFC read as enabled again right after being disabled. Turn on 'NFC Auto-Retry' on the main screen to retry automatically. A payment or wallet app may be turning it back on."
            )
        }

        Log.i(TAG, "🔄 Auto-retry enabled - attempting aggressive re-disable...")

        var retryCount = 0
        val maxRetries = 3 // Hard limit to prevent infinite loop

        while (retryCount < maxRetries) {
            retryCount++
            Log.d(TAG, "🔄 Retry attempt $retryCount of $maxRetries")

            super.disable()

            delay(300)

            val newState = getCurrentState()
            if (newState == FeatureState.DISABLED) {
                Log.i(TAG, "✅ Auto-retry successful on attempt $retryCount - NFC disabled")
                return PrivacyResult(
                    feature = feature,
                    success = true,
                    message = "NFC disabled (auto-retry succeeded on attempt $retryCount)"
                )
            }

            Log.d(TAG, "❌ Retry attempt $retryCount did not read back as disabled (state: $newState)")
        }

        // Says what was observed, not why. A read can also come back UNKNOWN mid-transition, so
        // claiming "still enabled" here would sometimes be telling the user something untrue.
        Log.w(TAG, "❌ Auto-retry exhausted all $maxRetries attempts - NFC never read back as disabled")
        return PrivacyResult(
            feature = feature,
            success = false,
            message = "NFC did not read back as disabled after $maxRetries retries. A payment or wallet app may be turning it back on - removing payment cards from it usually stops that."
        )
    }
}

