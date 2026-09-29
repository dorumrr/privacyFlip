package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.PreferenceManager
import kotlinx.coroutines.delay

// open so a test can stand in for the privileged shell and drive the real disable() path, which
// is where this class's logic actually lives.
open class NFCToggle(
    rootManager: RootManager,
    private val context: Context
) : BasePrivacyToggle(rootManager) {

    companion object {
        /**
         * Whether NFC still reads as on after we tried to turn it off, which is the only thing
         * that decides a retry.
         *
         * This used to require the disable attempt to have REPORTED success first. Once the base
         * class started reading the state back, an app fast enough to re-enable NFC before that
         * read made the attempt itself report failure, so the retry never ran in the one case it
         * exists for, and the log still said "successfully disabled".
         */
        @Suppress("UNUSED_PARAMETER")
        internal fun needsRetry(attemptReportedSuccess: Boolean, stateAfterDisable: FeatureState): Boolean =
            stateAfterDisable == FeatureState.ENABLED
    }

    override val feature = PrivacyFeature.NFC
    override val featureName = "NFC"

    override val enableCommands = commandsFor(true)
    override val disableCommands = commandsFor(false)

    // From Android 15 svc nfc can exit 0 without switching, and the NFC service's own commands are
    // disable-nfc/enable-nfc. '[persist]' keeps it off after a reboot, as svc nfc disable did.
    private fun commandsFor(on: Boolean): List<CommandSet> {
        val svc = CommandSet("svc nfc ${if (on) "enable" else "disable"}", description = "Service control method")
        if (Build.VERSION.SDK_INT < 35) {
            return listOf(svc, CommandSet("cmd nfc ${if (on) "enable" else "disable"}", description = "Modern cmd method (Android 8+)"))
        }
        return listOf(
            CommandSet(if (on) "cmd nfc enable-nfc" else "cmd nfc disable-nfc '[persist]'", description = "NFC service shell command"),
            svc
        )
    }

    // No "nfc_on" global setting: the NFC service keeps that flag in its own preferences, so
    // writing it switches nothing and reading it back only echoes the write.
    override val statusCommands = listOf(
        CommandSet("dumpsys nfc | grep 'mState='", description = "Primary status check")
    )

    override fun parseStatusOutput(output: String): FeatureState {
        Log.d(TAG, "🔍 Parsing NFC status output: '$output'")

        val state = when {
            output.contains("mState=on", ignoreCase = true) -> FeatureState.ENABLED
            output.contains("mState=off", ignoreCase = true) -> FeatureState.DISABLED
            // Read by the direction it is heading: "turning on" after a disable means NFC is coming back.
            output.contains("mState=turning on", ignoreCase = true) -> FeatureState.ENABLED
            output.contains("mState=turning off", ignoreCase = true) -> FeatureState.DISABLED
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

        if (!needsRetry(initialResult.success, actualState)) {
            // This read is fresher than the one the base class took, so it decides. "It stayed
            // off" is now said only when the state actually says off: a state that merely cannot
            // be read is not evidence of anything, and used to be reported as a success.
            if (actualState == FeatureState.DISABLED) {
                Log.d(TAG, "✅ NFC is off and stayed off")
                return PrivacyResult(feature, true, "NFC disabled", commandUsed = initialResult.commandUsed)
            }
            Log.w(TAG, "⚠️ NFC could not be confirmed off (state: $actualState)")
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
                message = "NFC still reads as on after the disable. Last attempt reported: ${initialResult.message}. " +
                    "A payment or wallet app may be turning it back on (turn on 'NFC Auto-Retry' on the main screen), " +
                    "or the privileged shell may be failing.",
                commandUsed = initialResult.commandUsed
            )
        }

        Log.i(TAG, "🔄 Auto-retry enabled - attempting aggressive re-disable...")

        var retryCount = 0
        val maxRetries = 3 // Hard limit to prevent infinite loop

        var lastAttempt: PrivacyResult? = null

        while (retryCount < maxRetries) {
            retryCount++
            Log.d(TAG, "🔄 Retry attempt $retryCount of $maxRetries")

            lastAttempt = super.disable()

            delay(300)

            val newState = getCurrentState()
            if (newState == FeatureState.DISABLED) {
                Log.i(TAG, "✅ Auto-retry successful on attempt $retryCount - NFC disabled")
                return PrivacyResult(
                    feature = feature,
                    success = true,
                    message = "NFC disabled (auto-retry succeeded on attempt $retryCount)",
                    commandUsed = lastAttempt.commandUsed
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
            // Names both plausible causes instead of asserting one. Root revoked mid-retry looks
            // identical from here to an app turning NFC back on, and sending the user after their
            // payment cards for a privilege failure wastes their time.
            message = "NFC did not read back as disabled after $maxRetries attempts. " +
                "Last attempt reported: ${lastAttempt?.message ?: "nothing"}. " +
                "A payment or wallet app may be turning it back on, or the privileged shell may be failing.",
            commandUsed = lastAttempt?.commandUsed ?: initialResult.commandUsed
        )
    }
}

