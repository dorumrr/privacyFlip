package io.github.dorumrr.privacyflip.privacy

import android.content.Context
import android.util.Log
import io.github.dorumrr.privacyflip.data.*
import io.github.dorumrr.privacyflip.root.RootManager
import io.github.dorumrr.privacyflip.util.DeviceDetector
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
     * Override disable() to add Samsung payment framework override detection and auto-retry.
     * 
     * Samsung devices with payment capabilities (Galaxy S, Note, Z series) have a system-level
     * framework that can re-enable NFC for payment/wallet functionality even after it's disabled.
     * This method detects the override and optionally retries the disable command.
     */
    override suspend fun disable(): PrivacyResult {
        Log.d(TAG, "📍 Starting NFC disable sequence...")
        
        // Execute standard disable command
        val initialResult = super.disable()
        
        // If not Samsung, return immediately (no override possible)
        if (!DeviceDetector.isSamsungWithPaymentOverride()) {
            return initialResult
        }
        
        Log.d(TAG, "🔍 Samsung device with payment override detected - checking for override...")
        
        // Wait for potential Samsung payment framework override
        delay(500)
        
        // Check actual NFC state after delay
        val actualState = getCurrentState()
        val wasOverridden = (initialResult.success && actualState == FeatureState.ENABLED)
        
        if (!wasOverridden) {
            Log.d(TAG, "✅ NFC successfully disabled (no Samsung override detected)")
            return initialResult
        }
        
        // Samsung payment framework overrode our disable command
        Log.w(TAG, "⚠️ Samsung payment framework overrode NFC disable (NFC re-enabled)")
        
        // Check if user enabled auto-retry feature
        val preferenceManager = PreferenceManager.getInstance(context)
        val autoRetryEnabled = preferenceManager.samsungNfcAutoRetry
        
        if (!autoRetryEnabled) {
            Log.i(TAG, "Auto-retry disabled by user preference - returning override warning")
            return PrivacyResult(
                feature = feature,
                success = false,
                message = "Samsung payment override detected. Enable 'Samsung Auto-Retry' in settings or disable payment cards in Google Wallet/Samsung Pay."
            )
        }
        
        // User enabled auto-retry - attempt aggressive re-disable
        Log.i(TAG, "🔄 Auto-retry enabled - attempting aggressive re-disable...")
        
        var retryCount = 0
        val maxRetries = 3 // Hard limit to prevent infinite loop
        
        while (retryCount < maxRetries) {
            retryCount++
            Log.d(TAG, "🔄 Retry attempt $retryCount of $maxRetries")
            
            // Try all disable methods in sequence
            super.disable()
            
            // Shorter delay between retries
            delay(300)
            
            // Check if it stuck this time
            val newState = getCurrentState()
            if (newState == FeatureState.DISABLED) {
                Log.i(TAG, "✅ Auto-retry successful on attempt $retryCount - NFC disabled")
                return PrivacyResult(
                    feature = feature,
                    success = true,
                    message = "NFC disabled (Samsung auto-retry succeeded on attempt $retryCount)"
                )
            }
            
            Log.d(TAG, "❌ Retry attempt $retryCount failed - Samsung framework re-enabled NFC")
        }
        
        // All retries exhausted
        Log.w(TAG, "❌ Auto-retry exhausted all $maxRetries attempts - Samsung payment override persists")
        return PrivacyResult(
            feature = feature,
            success = false,
            message = "Samsung payment override persists despite $maxRetries retry attempts. Disable payment cards in Google Wallet/Samsung Pay."
        )
    }
}

